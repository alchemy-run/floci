package io.github.hectorvent.floci.services.observabilityadmin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.observabilityadmin.model.AccountTelemetryState;
import io.github.hectorvent.floci.services.observabilityadmin.model.TelemetryRuleRecord;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;
import java.util.function.Supplier;

/**
 * CloudWatch Observability Admin (Smithy restJson1).
 *
 * <p>{@link ObservabilityAdminRoutingFilter} prefixes requests signed for
 * {@code observabilityadmin} so paths such as {@code /TagResource} do not
 * collide with Kinesis Video.
 */
@Path(ObservabilityAdminRoutingFilter.INTERNAL_PREFIX)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ObservabilityAdminController {

    private final ObservabilityAdminService service;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public ObservabilityAdminController(
            ObservabilityAdminService service, ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @POST
    @Path("/GetTelemetryEvaluationStatus")
    @Consumes(MediaType.WILDCARD)
    public Response getTelemetryEvaluationStatus(@Context HttpHeaders headers, String body) {
        return run(() -> {
            parse(body);
            return Response.ok(evaluationNode(service.getTelemetryEvaluationStatus(region(headers)))).build();
        });
    }

    @POST
    @Path("/StartTelemetryEvaluation")
    @Consumes(MediaType.WILDCARD)
    public Response startTelemetryEvaluation(@Context HttpHeaders headers, String body) {
        return run(() -> {
            service.startTelemetryEvaluation(region(headers), parse(body));
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @POST
    @Path("/StopTelemetryEvaluation")
    @Consumes(MediaType.WILDCARD)
    public Response stopTelemetryEvaluation(@Context HttpHeaders headers, String body) {
        return run(() -> {
            parse(body);
            service.stopTelemetryEvaluation(region(headers));
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @POST
    @Path("/GetTelemetryEnrichmentStatus")
    @Consumes(MediaType.WILDCARD)
    public Response getTelemetryEnrichmentStatus(@Context HttpHeaders headers, String body) {
        return run(() -> {
            parse(body);
            return Response.ok(enrichmentNode(service.getTelemetryEnrichmentStatus(region(headers)))).build();
        });
    }

    @POST
    @Path("/StartTelemetryEnrichment")
    @Consumes(MediaType.WILDCARD)
    public Response startTelemetryEnrichment(@Context HttpHeaders headers, String body) {
        return run(() -> {
            parse(body);
            return Response.ok(enrichmentNode(service.startTelemetryEnrichment(region(headers)))).build();
        });
    }

    @POST
    @Path("/StopTelemetryEnrichment")
    @Consumes(MediaType.WILDCARD)
    public Response stopTelemetryEnrichment(@Context HttpHeaders headers, String body) {
        return run(() -> {
            parse(body);
            return Response.ok(enrichmentNode(service.stopTelemetryEnrichment(region(headers)))).build();
        });
    }

    @POST
    @Path("/CreateTelemetryRule")
    public Response createTelemetryRule(@Context HttpHeaders headers, String body) {
        return run(() -> {
            TelemetryRuleRecord record = service.createTelemetryRule(region(headers), parse(body));
            ObjectNode response = objectMapper.createObjectNode();
            response.put("RuleArn", record.getRuleArn());
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/GetTelemetryRule")
    public Response getTelemetryRule(@Context HttpHeaders headers, String body) {
        return run(() -> Response.ok(ruleNode(service.getTelemetryRule(region(headers), parse(body)))).build());
    }

    @POST
    @Path("/UpdateTelemetryRule")
    public Response updateTelemetryRule(@Context HttpHeaders headers, String body) {
        return run(() -> {
            TelemetryRuleRecord record = service.updateTelemetryRule(region(headers), parse(body));
            ObjectNode response = objectMapper.createObjectNode();
            response.put("RuleArn", record.getRuleArn());
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/DeleteTelemetryRule")
    public Response deleteTelemetryRule(@Context HttpHeaders headers, String body) {
        return run(() -> {
            service.deleteTelemetryRule(region(headers), parse(body));
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @POST
    @Path("/ListTelemetryRules")
    @Consumes(MediaType.WILDCARD)
    public Response listTelemetryRules(@Context HttpHeaders headers, String body) {
        return run(() -> {
            ObservabilityAdminService.Page<TelemetryRuleRecord> page =
                    service.listTelemetryRules(region(headers), parse(body));
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode summaries = response.putArray("TelemetryRuleSummaries");
            for (TelemetryRuleRecord record : page.items()) {
                summaries.add(summaryNode(record));
            }
            if (page.nextToken() != null) {
                response.put("NextToken", page.nextToken());
            }
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/ListResourceTelemetry")
    @Consumes(MediaType.WILDCARD)
    public Response listResourceTelemetry(@Context HttpHeaders headers, String body) {
        return run(() -> {
            service.listResourceTelemetry(region(headers), parse(body));
            ObjectNode response = objectMapper.createObjectNode();
            response.putArray("TelemetryConfigurations");
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/ListTagsForResource")
    public Response listTagsForResource(@Context HttpHeaders headers, String body) {
        return run(() -> {
            ObjectNode response = objectMapper.createObjectNode();
            response.set("Tags", tagsNode(service.listTagsForResource(region(headers), parse(body))));
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/TagResource")
    public Response tagResource(@Context HttpHeaders headers, String body) {
        return run(() -> {
            service.tagResource(region(headers), parse(body));
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @POST
    @Path("/UntagResource")
    public Response untagResource(@Context HttpHeaders headers, String body) {
        return run(() -> {
            service.untagResource(region(headers), parse(body));
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    private ObjectNode evaluationNode(AccountTelemetryState state) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Status", state.getEvaluationStatus() == null
                ? AccountTelemetryState.NOT_STARTED
                : state.getEvaluationStatus());
        if (state.getHomeRegion() != null) {
            node.put("HomeRegion", state.getHomeRegion());
        }
        return node;
    }

    private ObjectNode enrichmentNode(AccountTelemetryState state) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Status", state.getEnrichmentStatus() == null
                ? AccountTelemetryState.ENRICHMENT_STOPPED
                : state.getEnrichmentStatus());
        return node;
    }

    private ObjectNode ruleNode(TelemetryRuleRecord record) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("RuleName", record.getRuleName());
        node.put("RuleArn", record.getRuleArn());
        node.put("CreatedTimeStamp", record.getCreatedTimeStamp());
        node.put("LastUpdateTimeStamp", record.getLastUpdateTimeStamp());
        if (record.getRule() != null) {
            node.set("TelemetryRule", record.getRule());
        }
        if (record.getHomeRegion() != null) {
            node.put("HomeRegion", record.getHomeRegion());
        }
        node.put("IsReplicated", record.isReplicated());
        return node;
    }

    private ObjectNode summaryNode(TelemetryRuleRecord record) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("RuleName", record.getRuleName());
        node.put("RuleArn", record.getRuleArn());
        node.put("CreatedTimeStamp", record.getCreatedTimeStamp());
        node.put("LastUpdateTimeStamp", record.getLastUpdateTimeStamp());
        JsonNode rule = record.getRule();
        if (rule != null && rule.isObject()) {
            copyText(rule, "ResourceType", node);
            copyText(rule, "TelemetryType", node);
            if (rule.has("TelemetrySourceTypes") && rule.get("TelemetrySourceTypes").isArray()) {
                node.set("TelemetrySourceTypes", rule.get("TelemetrySourceTypes").deepCopy());
            }
        }
        return node;
    }

    private static void copyText(JsonNode source, String field, ObjectNode target) {
        JsonNode value = source.get(field);
        if (value != null && value.isTextual()) {
            target.put(field, value.textValue());
        }
    }

    private ObjectNode tagsNode(Map<String, String> tags) {
        ObjectNode node = objectMapper.createObjectNode();
        if (tags != null) {
            tags.forEach(node::put);
        }
        return node;
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

    private String region(HttpHeaders headers) {
        return regionResolver.resolveRegion(headers);
    }

    private Response run(Supplier<Response> action) {
        try {
            return action.get();
        } catch (AwsException e) {
            return error(e);
        }
    }

    private static Response error(AwsException exception) {
        return Response.status(exception.getHttpStatus())
                .type(MediaType.APPLICATION_JSON)
                .header("X-Amzn-Errortype", exception.jsonType())
                .entity(new AwsErrorResponse(exception.jsonType(), exception.getMessage()))
                .build();
    }
}
