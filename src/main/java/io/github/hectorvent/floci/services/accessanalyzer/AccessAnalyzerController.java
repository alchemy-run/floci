package io.github.hectorvent.floci.services.accessanalyzer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.accessanalyzer.model.Analyzer;
import io.github.hectorvent.floci.services.accessanalyzer.model.ArchiveRule;
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
 * IAM Access Analyzer restJson1.
 *
 * <p>Literal {@code /analyzer}, {@code /policy}, {@code /findingv2}, {@code /archive-rule}
 * and {@code /analyzed-resource} paths take JAX-RS precedence over S3's {@code /{bucket}}
 * catch-all. Tag APIs share {@code /tags/{arn}} and are dispatched by {@code SharedTagsController}.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AccessAnalyzerController {

    private final AccessAnalyzerService service;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public AccessAnalyzerController(
            AccessAnalyzerService service, ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @PUT
    @Path("/analyzer")
    public Response createAnalyzer(@Context HttpHeaders headers, String body) {
        Analyzer analyzer = service.createAnalyzer(regionResolver.resolveRegion(headers), parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("arn", analyzer.getArn());
        return Response.ok(response).build();
    }

    @GET
    @Path("/analyzer")
    @Consumes(MediaType.WILDCARD)
    public Response listAnalyzers(
            @Context HttpHeaders headers,
            @QueryParam("type") String type,
            @QueryParam("maxResults") String maxResults,
            @QueryParam("nextToken") String nextToken) {
        AccessAnalyzerService.Page<Analyzer> page = service.listAnalyzers(
                regionResolver.resolveRegion(headers), type, maxResults, nextToken);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode analyzers = response.putArray("analyzers");
        for (Analyzer analyzer : page.items()) {
            analyzers.add(toAnalyzer(analyzer));
        }
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/analyzer/{analyzerName}")
    @Consumes(MediaType.WILDCARD)
    public Response getAnalyzer(@Context HttpHeaders headers, @PathParam("analyzerName") String analyzerName) {
        Analyzer analyzer = service.getAnalyzer(regionResolver.resolveRegion(headers), analyzerName);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("analyzer", toAnalyzer(analyzer));
        return Response.ok(response).build();
    }

    @PUT
    @Path("/analyzer/{analyzerName}")
    public Response updateAnalyzer(
            @Context HttpHeaders headers, @PathParam("analyzerName") String analyzerName, String body) {
        Analyzer analyzer = service.updateAnalyzer(regionResolver.resolveRegion(headers), analyzerName, parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        if (analyzer.getConfiguration() != null) {
            response.set("configuration", analyzer.getConfiguration());
        }
        return Response.ok(response).build();
    }

    @DELETE
    @Path("/analyzer/{analyzerName}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteAnalyzer(@Context HttpHeaders headers, @PathParam("analyzerName") String analyzerName) {
        service.deleteAnalyzer(regionResolver.resolveRegion(headers), analyzerName);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/analyzer/findings/statistics")
    public Response getFindingsStatistics(@Context HttpHeaders headers, String body) {
        return Response.ok(service.getFindingsStatistics(regionResolver.resolveRegion(headers), parse(body))).build();
    }

    @PUT
    @Path("/analyzer/{analyzerName}/archive-rule")
    public Response createArchiveRule(
            @Context HttpHeaders headers, @PathParam("analyzerName") String analyzerName, String body) {
        service.createArchiveRule(regionResolver.resolveRegion(headers), analyzerName, parse(body));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @GET
    @Path("/analyzer/{analyzerName}/archive-rule")
    @Consumes(MediaType.WILDCARD)
    public Response listArchiveRules(
            @Context HttpHeaders headers,
            @PathParam("analyzerName") String analyzerName,
            @QueryParam("maxResults") String maxResults,
            @QueryParam("nextToken") String nextToken) {
        AccessAnalyzerService.Page<ArchiveRule> page = service.listArchiveRules(
                regionResolver.resolveRegion(headers), analyzerName, maxResults, nextToken);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode rules = response.putArray("archiveRules");
        for (ArchiveRule rule : page.items()) {
            rules.add(toArchiveRule(rule));
        }
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/analyzer/{analyzerName}/archive-rule/{ruleName}")
    @Consumes(MediaType.WILDCARD)
    public Response getArchiveRule(
            @Context HttpHeaders headers,
            @PathParam("analyzerName") String analyzerName,
            @PathParam("ruleName") String ruleName) {
        ArchiveRule rule = service.getArchiveRule(regionResolver.resolveRegion(headers), analyzerName, ruleName);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("archiveRule", toArchiveRule(rule));
        return Response.ok(response).build();
    }

    @PUT
    @Path("/analyzer/{analyzerName}/archive-rule/{ruleName}")
    public Response updateArchiveRule(
            @Context HttpHeaders headers,
            @PathParam("analyzerName") String analyzerName,
            @PathParam("ruleName") String ruleName,
            String body) {
        service.updateArchiveRule(regionResolver.resolveRegion(headers), analyzerName, ruleName, parse(body));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @DELETE
    @Path("/analyzer/{analyzerName}/archive-rule/{ruleName}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteArchiveRule(
            @Context HttpHeaders headers,
            @PathParam("analyzerName") String analyzerName,
            @PathParam("ruleName") String ruleName) {
        service.deleteArchiveRule(regionResolver.resolveRegion(headers), analyzerName, ruleName);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @PUT
    @Path("/archive-rule")
    public Response applyArchiveRule(@Context HttpHeaders headers, String body) {
        service.applyArchiveRule(regionResolver.resolveRegion(headers), parse(body));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/policy/validation")
    public Response validatePolicy(String body) {
        return Response.ok(service.validatePolicy(parse(body))).build();
    }

    @POST
    @Path("/policy/check-no-new-access")
    public Response checkNoNewAccess(String body) {
        return Response.ok(service.checkNoNewAccess(parse(body))).build();
    }

    @POST
    @Path("/policy/check-access-not-granted")
    public Response checkAccessNotGranted(String body) {
        return Response.ok(service.checkAccessNotGranted(parse(body))).build();
    }

    @POST
    @Path("/policy/check-no-public-access")
    public Response checkNoPublicAccess(String body) {
        return Response.ok(service.checkNoPublicAccess(parse(body))).build();
    }

    @GET
    @Path("/policy/generation")
    @Consumes(MediaType.WILDCARD)
    public Response listPolicyGenerations() {
        return Response.ok(service.listPolicyGenerations()).build();
    }

    @PUT
    @Path("/policy/generation")
    public Response startPolicyGeneration(String body) {
        return Response.ok(service.startPolicyGeneration(parse(body))).build();
    }

    @GET
    @Path("/policy/generation/{jobId}")
    @Consumes(MediaType.WILDCARD)
    public Response getGeneratedPolicy(@PathParam("jobId") String jobId) {
        service.getGeneratedPolicy(jobId);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @PUT
    @Path("/policy/generation/{jobId}")
    public Response cancelPolicyGeneration(@PathParam("jobId") String jobId) {
        service.cancelPolicyGeneration(jobId);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/findingv2")
    public Response listFindingsV2(@Context HttpHeaders headers, String body) {
        return Response.ok(service.listFindingsV2(regionResolver.resolveRegion(headers), parse(body))).build();
    }

    @GET
    @Path("/findingv2/{id}")
    @Consumes(MediaType.WILDCARD)
    public Response getFindingV2(
            @Context HttpHeaders headers,
            @PathParam("id") String id,
            @QueryParam("analyzerArn") String analyzerArn) {
        service.getFindingV2(regionResolver.resolveRegion(headers), id, analyzerArn);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/analyzed-resource")
    public Response listAnalyzedResources(@Context HttpHeaders headers, String body) {
        return Response.ok(service.listAnalyzedResources(regionResolver.resolveRegion(headers), parse(body))).build();
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode request = objectMapper.readTree(body);
            if (request == null || !request.isObject()) {
                throw AccessAnalyzerService.validation("Request body must be a JSON object.", "fieldValidationFailed");
            }
            return request;
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw AccessAnalyzerService.validation("Request body is not valid JSON.", "cannotParse");
        }
    }

    private ObjectNode toAnalyzer(Analyzer analyzer) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("arn", analyzer.getArn());
        node.put("name", analyzer.getName());
        node.put("type", analyzer.getType());
        node.put("createdAt", analyzer.getCreatedAt());
        node.put("status", analyzer.getStatus());
        ObjectNode tags = node.putObject("tags");
        if (analyzer.getTags() != null) {
            analyzer.getTags().forEach(tags::put);
        }
        if (analyzer.getConfiguration() != null) {
            node.set("configuration", analyzer.getConfiguration());
        }
        return node;
    }

    private ObjectNode toArchiveRule(ArchiveRule rule) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("ruleName", rule.getRuleName());
        node.set("filter", rule.getFilter() == null
                ? objectMapper.createObjectNode()
                : objectMapper.valueToTree(rule.getFilter()));
        node.put("createdAt", rule.getCreatedAt());
        node.put("updatedAt", rule.getUpdatedAt());
        return node;
    }
}
