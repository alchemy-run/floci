package io.github.hectorvent.floci.services.accessanalyzer;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.JsonErrorResponseUtils;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.accessanalyzer.model.Analyzer;
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
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Set;

@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AccessAnalyzerController {
    private final AccessAnalyzerService accessAnalyzerService;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public AccessAnalyzerController(AccessAnalyzerService accessAnalyzerService,
                                    RegionResolver regionResolver, ObjectMapper objectMapper) {
        this.accessAnalyzerService = accessAnalyzerService;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @GET
    @Path("/analyzer")
    public Response listAnalyzers(@Context HttpHeaders headers,
                                  @QueryParam("type") String type,
                                  @QueryParam("maxResults") String maxResults,
                                  @QueryParam("nextToken") String nextToken) {
        String region = regionResolver.resolveRegion(headers);
        PaginatedResult<Analyzer> page = accessAnalyzerService.listAnalyzers(
                region, type, AccessAnalyzerService.parseMaxResults(maxResults), nextToken);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("analyzers");
        page.items().forEach(analyzer -> items.add(analyzerSummary(analyzer)));
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    @PUT
    @Path("/analyzer")
    public Response createAnalyzer(@Context HttpHeaders headers, String body) {
        Analyzer analyzer = accessAnalyzerService.createAnalyzer(readTree(body), regionResolver.resolveRegion(headers));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("arn", analyzer.getArn());
        return Response.ok(response).build();
    }

    @DELETE
    @Path("/analyzer/{analyzerName}")
    public Response deleteAnalyzer(@Context HttpHeaders headers, @PathParam("analyzerName") String analyzerName) {
        accessAnalyzerService.deleteAnalyzer(regionResolver.resolveRegion(headers), analyzerName);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @GET
    @Path("/analyzer/{analyzerName}")
    public Response getAnalyzer(@Context HttpHeaders headers, @PathParam("analyzerName") String name) {
        Analyzer analyzer = accessAnalyzerService.getAnalyzer(regionResolver.resolveRegion(headers), name);
        return Response.ok(objectMapper.createObjectNode().set("analyzer", analyzerSummary(analyzer))).build();
    }

    @PUT
    @Path("/analyzer/{analyzerName}")
    public Response updateAnalyzer(@Context HttpHeaders headers, @PathParam("analyzerName") String name, String body) {
        accessAnalyzerService.getAnalyzer(regionResolver.resolveRegion(headers), name);
        readTree(body);
        throw AccessAnalyzerService.unsupported("UpdateAnalyzer configuration");
    }

    @PUT
    @Path("/analyzer/{analyzerName}/archive-rule")
    public Response createArchiveRule(@Context HttpHeaders headers, @PathParam("analyzerName") String name, String body) {
        accessAnalyzerService.createArchiveRule(regionResolver.resolveRegion(headers), name, readTree(body));
        return empty();
    }

    @GET
    @Path("/analyzer/{analyzerName}/archive-rule/{ruleName}")
    public Response getArchiveRule(@Context HttpHeaders headers, @PathParam("analyzerName") String name,
                                   @PathParam("ruleName") String ruleName) {
        JsonNode rule = accessAnalyzerService.getArchiveRule(regionResolver.resolveRegion(headers), name, ruleName);
        return Response.ok(objectMapper.createObjectNode().set("archiveRule", rule)).build();
    }

    @GET
    @Path("/analyzer/{analyzerName}/archive-rule")
    public Response listArchiveRules(@Context HttpHeaders headers, @PathParam("analyzerName") String name,
                                     @QueryParam("maxResults") String maxResults,
                                     @QueryParam("nextToken") String nextToken) {
        PaginatedResult<JsonNode> page = accessAnalyzerService.listArchiveRules(regionResolver.resolveRegion(headers),
                name, AccessAnalyzerService.parseMaxResults(maxResults), nextToken);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode rules = response.putArray("archiveRules");
        page.items().forEach(rules::add);
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    @PUT
    @Path("/analyzer/{analyzerName}/archive-rule/{ruleName}")
    public Response updateArchiveRule(@Context HttpHeaders headers, @PathParam("analyzerName") String name,
                                      @PathParam("ruleName") String ruleName, String body) {
        accessAnalyzerService.updateArchiveRule(regionResolver.resolveRegion(headers), name, ruleName, readTree(body));
        return empty();
    }

    @DELETE
    @Path("/analyzer/{analyzerName}/archive-rule/{ruleName}")
    public Response deleteArchiveRule(@Context HttpHeaders headers, @PathParam("analyzerName") String name,
                                      @PathParam("ruleName") String ruleName) {
        accessAnalyzerService.deleteArchiveRule(regionResolver.resolveRegion(headers), name, ruleName);
        return empty();
    }

    @PUT
    @Path("/archive-rule")
    public Response applyArchiveRule(@Context HttpHeaders headers, String body) {
        accessAnalyzerService.applyArchiveRule(regionResolver.resolveRegion(headers), readTree(body));
        return empty();
    }

    @POST
    @Path("/policy/validation")
    public Response validatePolicy(String body, @QueryParam("maxResults") String maxResults,
                                    @QueryParam("nextToken") String nextToken) {
        validateEmptyPage(maxResults, nextToken);
        return Response.ok(PolicyEvaluator.validate(readTree(body))).build();
    }

    @POST
    @Path("/policy/check-no-new-access")
    public Response checkNoNewAccess(String body) {
        return Response.ok(PolicyEvaluator.checkNoNewAccess(readTree(body))).build();
    }

    @POST
    @Path("/policy/check-access-not-granted")
    public Response checkAccessNotGranted(String body) {
        return Response.ok(PolicyEvaluator.checkAccessNotGranted(readTree(body))).build();
    }

    @POST
    @Path("/policy/check-no-public-access")
    public Response checkNoPublicAccess(String body) {
        return Response.ok(PolicyEvaluator.checkNoPublicAccess(readTree(body))).build();
    }

    @POST
    @Path("/finding")
    public Response listFindings(@Context HttpHeaders headers, String body) {
        JsonNode request = readTree(body);
        Analyzer analyzer = requireAnalyzer(headers, request);
        if (!Set.of("ACCOUNT", "ORGANIZATION").contains(analyzer.getType())) {
            throw AccessAnalyzerService.validation("ListFindings requires an external-access analyzer; use ListFindingsV2.");
        }
        validateEmptyPage(request);
        return emptyList("findings");
    }

    @POST
    @Path("/findingv2")
    public Response listFindingsV2(@Context HttpHeaders headers, String body) {
        JsonNode request = readTree(body);
        requireAnalyzer(headers, request);
        validateEmptyPage(request);
        return emptyList("findings");
    }

    @POST
    @Path("/analyzed-resource")
    public Response listAnalyzedResources(@Context HttpHeaders headers, String body) {
        JsonNode request = readTree(body);
        requireAnalyzer(headers, request);
        validateEmptyPage(request);
        return emptyList("analyzedResources");
    }

    @GET
    @Path("/finding/{id}")
    public Response getFinding(@Context HttpHeaders headers, @PathParam("id") String id,
                                @QueryParam("analyzerArn") String arn) {
        return getFindingV2(headers, id, arn);
    }

    @GET
    @Path("/findingv2/{id}")
    public Response getFindingV2(@Context HttpHeaders headers, @PathParam("id") String id,
                                  @QueryParam("analyzerArn") String arn) {
        accessAnalyzerService.getAnalyzerByArn(regionResolver.resolveRegion(headers), arn);
        throw AccessAnalyzerService.notFound(id, "AWS::AccessAnalyzer::Finding");
    }

    @GET
    @Path("/recommendation/{id}")
    public Response getFindingRecommendation(@Context HttpHeaders headers, @PathParam("id") String id,
                                             @QueryParam("analyzerArn") String arn) {
        return getFindingV2(headers, id, arn);
    }

    @POST
    @Path("/recommendation/{id}")
    public Response generateRecommendation(@Context HttpHeaders headers, @PathParam("id") String id,
                                            @QueryParam("analyzerArn") String arn) {
        accessAnalyzerService.getAnalyzerByArn(regionResolver.resolveRegion(headers), arn);
        throw AccessAnalyzerService.notFound(id, "AWS::AccessAnalyzer::Finding");
    }

    @POST
    @Path("/analyzer/findings/statistics")
    public Response getFindingsStatistics(@Context HttpHeaders headers, String body) {
        Analyzer analyzer = requireAnalyzer(headers, readTree(body));
        String key = analyzer.getType().endsWith("_UNUSED_ACCESS") ? "unusedAccessFindingsStatistics"
                : analyzer.getType().endsWith("_INTERNAL_ACCESS") ? "internalAccessFindingsStatistics"
                : "externalAccessFindingsStatistics";
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("findingsStatistics").addObject().putObject(key)
                .put("totalActiveFindings", 0).put("totalArchivedFindings", 0).put("totalResolvedFindings", 0);
        return Response.ok(response).build();
    }

    @PUT
    @Path("/finding")
    public Response updateFindings(@Context HttpHeaders headers, String body) {
        requireAnalyzer(headers, readTree(body));
        throw AccessAnalyzerService.unsupported("UpdateFindings");
    }

    @GET
    @Path("/analyzed-resource")
    public Response getAnalyzedResource(@Context HttpHeaders headers, @QueryParam("analyzerArn") String arn,
                                        @QueryParam("resourceArn") String resourceArn) {
        accessAnalyzerService.getAnalyzerByArn(regionResolver.resolveRegion(headers), arn);
        if (resourceArn == null) {
            throw AccessAnalyzerService.validation("resourceArn is required.");
        }
        throw AccessAnalyzerService.notFound(resourceArn, "AWS::AccessAnalyzer::AnalyzedResource");
    }

    @POST
    @Path("/resource/scan")
    public Response startResourceScan(@Context HttpHeaders headers, String body) {
        requireAnalyzer(headers, readTree(body));
        throw AccessAnalyzerService.unsupported("StartResourceScan");
    }

    @PUT
    @Path("/access-preview")
    public Response createAccessPreview(@Context HttpHeaders headers, String body) {
        requireAnalyzer(headers, readTree(body));
        throw AccessAnalyzerService.unsupported("CreateAccessPreview");
    }

    @GET
    @Path("/access-preview")
    public Response listAccessPreviews(@Context HttpHeaders headers, @QueryParam("analyzerArn") String arn,
                                       @QueryParam("maxResults") String maxResults,
                                       @QueryParam("nextToken") String nextToken) {
        accessAnalyzerService.getAnalyzerByArn(regionResolver.resolveRegion(headers), arn);
        validateEmptyPage(maxResults, nextToken);
        return emptyList("accessPreviews");
    }

    @GET
    @Path("/access-preview/{id}")
    public Response getAccessPreview(@Context HttpHeaders headers, @QueryParam("analyzerArn") String arn,
                                     @PathParam("id") String id) {
        accessAnalyzerService.getAnalyzerByArn(regionResolver.resolveRegion(headers), arn);
        throw AccessAnalyzerService.notFound(id, "AWS::AccessAnalyzer::AccessPreview");
    }

    @POST
    @Path("/access-preview/{id}")
    public Response listAccessPreviewFindings(@Context HttpHeaders headers, @PathParam("id") String id, String body) {
        requireAnalyzer(headers, readTree(body));
        throw AccessAnalyzerService.notFound(id, "AWS::AccessAnalyzer::AccessPreview");
    }

    @GET
    @Path("/policy/generation")
    public Response listPolicyGenerations(@QueryParam("maxResults") String maxResults,
                                          @QueryParam("nextToken") String nextToken) {
        validateEmptyPage(maxResults, nextToken);
        return emptyList("policyGenerations");
    }

    @PUT
    @Path("/policy/generation")
    public Response startPolicyGeneration(String body) {
        JsonNode request = readTree(body);
        if (!request.hasNonNull("cloudTrailDetails")) {
            throw AccessAnalyzerService.validation("Missing cloudTrailDetails");
        }
        throw AccessAnalyzerService.unsupported("CloudTrail policy generation");
    }

    @GET
    @Path("/policy/generation/{jobId}")
    public Response getGeneratedPolicy(@PathParam("jobId") String jobId) {
        throw AccessAnalyzerService.validation("No policy generation job exists with jobId " + jobId);
    }

    @PUT
    @Path("/policy/generation/{jobId}")
    public Response cancelPolicyGeneration(@PathParam("jobId") String jobId) {
        throw AccessAnalyzerService.validation("No policy generation job exists with jobId " + jobId);
    }

    private Analyzer requireAnalyzer(HttpHeaders headers, JsonNode request) {
        return accessAnalyzerService.getAnalyzerByArn(regionResolver.resolveRegion(headers),
                AccessAnalyzerService.text(request, "analyzerArn"));
    }

    private void validateEmptyPage(JsonNode request) {
        JsonNode max = request.get("maxResults");
        if (max != null && !max.isIntegralNumber()) {
            throw AccessAnalyzerService.validation("maxResults must be an integer.");
        }
        validateEmptyPage(max == null ? null : max.asText(), AccessAnalyzerService.text(request, "nextToken"));
    }

    private void validateEmptyPage(String maxResults, String nextToken) {
        Integer max = AccessAnalyzerService.parseMaxResults(maxResults);
        if (max != null && (max < 1 || max > 1000)) {
            throw AccessAnalyzerService.validation("maxResults must be between 1 and 1000.");
        }
        if (nextToken != null) {
            throw AccessAnalyzerService.validation("Invalid nextToken: no further results exist.");
        }
    }

    private Response emptyList(String field) {
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray(field);
        return Response.ok(response).build();
    }

    private Response empty() {
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private ObjectNode analyzerSummary(Analyzer analyzer) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("arn", analyzer.getArn());
        node.put("name", analyzer.getName());
        node.put("type", analyzer.getType());
        node.put("status", analyzer.getStatus());
        node.put("createdAt", analyzer.getCreatedAt());
        if (analyzer.getConfiguration() != null) {
            node.set("configuration", analyzer.getConfiguration());
        }
        node.set("tags", objectMapper.valueToTree(analyzer.getTags()));
        return node;
    }

    private JsonNode readTree(String body) {
        JsonNode request;
        try {
            request = objectMapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (Exception e) {
            throw new WebApplicationException(JsonErrorResponseUtils.createSerializationErrorResponse());
        }
        if (request == null || !request.isObject()) {
            throw AccessAnalyzerService.validation("Request payload must be a JSON object.");
        }
        return request;
    }
}
