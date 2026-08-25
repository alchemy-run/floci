package io.github.hectorvent.floci.services.applicationsignals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.applicationsignals.model.AccountSignalsState;
import io.github.hectorvent.floci.services.applicationsignals.model.GroupingAttributeDefinition;
import io.github.hectorvent.floci.services.applicationsignals.model.InstrumentationConfiguration;
import io.github.hectorvent.floci.services.applicationsignals.model.ServiceLevelObjective;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CloudWatch Application Signals (Smithy restJson1).
 *
 * <p>{@link ApplicationSignalsRoutingFilter} prefixes requests signed for
 * {@code application-signals} so paths such as {@code /services} and {@code /tags}
 * do not collide with Audit Manager or {@code SharedTagsController}.
 */
@Path("/application-signals")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ApplicationSignalsController {

    private final ApplicationSignalsService service;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public ApplicationSignalsController(
            ApplicationSignalsService service, ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @PUT
    @Path("/grouping-configuration")
    public Response putGroupingConfiguration(@Context HttpHeaders headers, String body) {
        AccountSignalsState state = service.putGroupingConfiguration(region(headers), parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("GroupingConfiguration", groupingConfigurationNode(state));
        return Response.ok(response).build();
    }

    @POST
    @Path("/grouping-attribute-definitions")
    @Consumes(MediaType.WILDCARD)
    public Response listGroupingAttributeDefinitions(@Context HttpHeaders headers, String body) {
        AccountSignalsState state = service.listGroupingAttributeDefinitions(region(headers));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("GroupingAttributeDefinitions", groupingDefinitionsNode(state));
        if (state.getGroupingUpdatedAt() != null) {
            response.put("UpdatedAt", state.getGroupingUpdatedAt());
        }
        return Response.ok(response).build();
    }

    @DELETE
    @Path("/grouping-configuration")
    @Consumes(MediaType.WILDCARD)
    public Response deleteGroupingConfiguration(@Context HttpHeaders headers) {
        service.deleteGroupingConfiguration(region(headers));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/start-discovery")
    @Consumes(MediaType.WILDCARD)
    public Response startDiscovery(@Context HttpHeaders headers, String body) {
        service.startDiscovery(region(headers));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/slo")
    public Response createSlo(@Context HttpHeaders headers, String body) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Slo", toSlo(service.createSlo(region(headers), parse(body))));
        return Response.ok(response).build();
    }

    @GET
    @Path("/slo/{Id: .+}/exclusion-windows")
    @Consumes(MediaType.WILDCARD)
    public Response listExclusionWindows(@Context HttpHeaders headers, @PathParam("Id") String id) {
        return Response.ok(service.listExclusionWindows(region(headers), id)).build();
    }

    @GET
    @Path("/slo/{Id: .+}")
    @Consumes(MediaType.WILDCARD)
    public Response getSlo(@Context HttpHeaders headers, @PathParam("Id") String id) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Slo", toSlo(service.getSlo(region(headers), id)));
        return Response.ok(response).build();
    }

    @PATCH
    @Path("/slo/{Id: .+}")
    public Response updateSlo(@Context HttpHeaders headers, @PathParam("Id") String id, String body) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Slo", toSlo(service.updateSlo(region(headers), id, parse(body))));
        return Response.ok(response).build();
    }

    @DELETE
    @Path("/slo/{Id: .+}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteSlo(@Context HttpHeaders headers, @PathParam("Id") String id) {
        service.deleteSlo(region(headers), id);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/slos")
    public Response listSlos(@Context HttpHeaders headers, String body) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode summaries = response.putArray("SloSummaries");
        for (ServiceLevelObjective slo : service.listSlos(region(headers))) {
            ObjectNode summary = summaries.addObject();
            summary.put("Arn", slo.getArn());
            summary.put("Name", slo.getName());
            summary.put("CreatedTime", slo.getCreatedTime());
            if (slo.getEvaluationType() != null) {
                summary.put("EvaluationType", slo.getEvaluationType());
            }
            if (slo.getMetricSourceType() != null) {
                summary.put("MetricSourceType", slo.getMetricSourceType());
            }
        }
        return Response.ok(response).build();
    }

    @POST
    @Path("/budget-report")
    public Response budgetReport(@Context HttpHeaders headers, String body) {
        return Response.ok(service.budgetReport(region(headers), parse(body))).build();
    }

    @PATCH
    @Path("/exclusion-windows")
    public Response updateExclusionWindows(@Context HttpHeaders headers, String body) {
        return Response.ok(service.updateExclusionWindows(region(headers), parse(body))).build();
    }

    @GET
    @Path("/services")
    @Consumes(MediaType.WILDCARD)
    public Response listServices(
            @QueryParam("StartTime") String startTime, @QueryParam("EndTime") String endTime) {
        return Response.ok(service.emptyDiscovery(times(startTime, endTime), "ServiceSummaries")).build();
    }

    @POST
    @Path("/service")
    public Response getService(
            @QueryParam("StartTime") String startTime,
            @QueryParam("EndTime") String endTime,
            String body) {
        return Response.ok(service.getDiscoveredService(withTimes(parse(body), startTime, endTime))).build();
    }

    @POST
    @Path("/service-dependencies")
    public Response listDependencies(
            @QueryParam("StartTime") String startTime,
            @QueryParam("EndTime") String endTime,
            String body) {
        return Response.ok(service.emptyDiscovery(
                withTimes(parse(body), startTime, endTime), "ServiceDependencies")).build();
    }

    @POST
    @Path("/service-dependents")
    public Response listDependents(
            @QueryParam("StartTime") String startTime,
            @QueryParam("EndTime") String endTime,
            String body) {
        return Response.ok(service.emptyDiscovery(
                withTimes(parse(body), startTime, endTime), "ServiceDependents")).build();
    }

    @POST
    @Path("/service-operations")
    public Response listOperations(
            @QueryParam("StartTime") String startTime,
            @QueryParam("EndTime") String endTime,
            String body) {
        return Response.ok(service.emptyDiscovery(
                withTimes(parse(body), startTime, endTime), "ServiceOperations")).build();
    }

    @POST
    @Path("/service/states")
    public Response listStates(String body) {
        return Response.ok(service.emptyDiscovery(parse(body), "ServiceStates")).build();
    }

    @POST
    @Path("/events")
    public Response listEntityEvents(String body) {
        return Response.ok(service.emptyDiscovery(parse(body), "ChangeEvents")).build();
    }

    @POST
    @Path("/auditFindings")
    public Response listAuditFindings(
            @QueryParam("StartTime") String startTime,
            @QueryParam("EndTime") String endTime,
            String body) {
        return Response.ok(service.listAuditFindings(withTimes(parse(body), startTime, endTime))).build();
    }

    @GET
    @Path("/tags")
    @Consumes(MediaType.WILDCARD)
    public Response listTags(
            @Context HttpHeaders headers,
            @QueryParam("ResourceArn") String resourceArn,
            @QueryParam("resourceArn") String resourceArnLower) {
        String arn = resourceArn != null && !resourceArn.isBlank() ? resourceArn : resourceArnLower;
        if (arn == null || arn.isBlank()) {
            throw new AwsException("ValidationException", "ResourceArn is required.", 400);
        }
        return Response.ok(service.listTagsForResource(region(headers), arn)).build();
    }

    @POST
    @Path("/create-instrumentation-configuration")
    public Response createInstrumentationConfiguration(@Context HttpHeaders headers, String body) {
        return Response.ok(toNode(service.createInstrumentationConfiguration(region(headers), parse(body)))).build();
    }

    @POST
    @Path("/get-instrumentation-configuration")
    public Response getInstrumentationConfiguration(@Context HttpHeaders headers, String body) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Configuration", toNode(service.getInstrumentationConfiguration(region(headers), parse(body))));
        return Response.ok(response).build();
    }

    @POST
    @Path("/delete-instrumentation-configuration")
    public Response deleteInstrumentationConfiguration(@Context HttpHeaders headers, String body) {
        service.deleteInstrumentationConfiguration(region(headers), parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("DeletionStatus", "DELETED");
        return Response.ok(response).build();
    }

    @POST
    @Path("/get-instrumentation-configuration-status")
    public Response getInstrumentationStatus(@Context HttpHeaders headers, String body) {
        return Response.ok(service.getInstrumentationStatus(region(headers), parse(body))).build();
    }

    @POST
    @Path("/tag-resource")
    public Response tagResource(@Context HttpHeaders headers, String body) {
        JsonNode request = parse(body);
        service.tagResource(region(headers), requireArn(request), readTags(request.get("Tags")));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/untag-resource")
    public Response untagResource(@Context HttpHeaders headers, String body) {
        JsonNode request = parse(body);
        service.untagResource(region(headers), requireArn(request), readTagKeys(request.get("TagKeys")));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private String region(HttpHeaders headers) {
        return regionResolver.resolveRegion(headers);
    }

    private JsonNode times(String startTime, String endTime) {
        return withTimes(objectMapper.createObjectNode(), startTime, endTime);
    }

    private JsonNode withTimes(JsonNode request, String startTime, String endTime) {
        ObjectNode node = request != null && request.isObject()
                ? (ObjectNode) request
                : objectMapper.createObjectNode();
        putEpoch(node, "StartTime", startTime);
        putEpoch(node, "EndTime", endTime);
        return node;
    }

    private static void putEpoch(ObjectNode node, String field, String value) {
        if (value == null || value.isBlank() || node.has(field)) {
            return;
        }
        try {
            node.put(field, Long.parseLong(value));
        } catch (NumberFormatException e) {
            node.put(field, value);
        }
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

    private ObjectNode groupingConfigurationNode(AccountSignalsState state) {
        ObjectNode node = objectMapper.createObjectNode();
        node.set("GroupingAttributeDefinitions", groupingDefinitionsNode(state));
        if (state.getGroupingUpdatedAt() != null) {
            node.put("UpdatedAt", state.getGroupingUpdatedAt());
        }
        return node;
    }

    private ArrayNode groupingDefinitionsNode(AccountSignalsState state) {
        ArrayNode array = objectMapper.createArrayNode();
        List<GroupingAttributeDefinition> definitions = state.getGroupingAttributeDefinitions();
        if (definitions == null) {
            return array;
        }
        for (GroupingAttributeDefinition definition : definitions) {
            ObjectNode node = array.addObject();
            node.put("GroupingName", definition.getGroupingName());
            if (definition.getGroupingSourceKeys() != null) {
                ArrayNode keys = node.putArray("GroupingSourceKeys");
                definition.getGroupingSourceKeys().forEach(keys::add);
            }
            if (definition.getDefaultGroupingValue() != null) {
                node.put("DefaultGroupingValue", definition.getDefaultGroupingValue());
            }
        }
        return array;
    }

    private ObjectNode toNode(InstrumentationConfiguration config) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("InstrumentationType", config.getInstrumentationType());
        node.put("Service", config.getService());
        node.put("Environment", config.getEnvironment());
        node.put("SignalType", config.getSignalType());
        if (config.getLocation() != null) {
            node.set("Location", config.getLocation());
        }
        node.put("LocationHash", config.getLocationHash());
        if (config.getDescription() != null) {
            node.put("Description", config.getDescription());
        }
        if (config.getExpiresAt() != null) {
            node.put("ExpiresAt", config.getExpiresAt());
        }
        if (config.getAttributeFilters() != null) {
            node.set("AttributeFilters", config.getAttributeFilters());
        }
        if (config.getCaptureConfiguration() != null) {
            node.set("CaptureConfiguration", config.getCaptureConfiguration());
        }
        node.put("CreatedAt", config.getCreatedAt());
        node.put("ARN", config.getArn());
        return node;
    }

    private ObjectNode toSlo(ServiceLevelObjective slo) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Arn", slo.getArn());
        node.put("Name", slo.getName());
        if (slo.getDescription() != null) {
            node.put("Description", slo.getDescription());
        }
        node.put("CreatedTime", slo.getCreatedTime());
        node.put("LastUpdatedTime", slo.getLastUpdatedTime());
        if (slo.getEvaluationType() != null) {
            node.put("EvaluationType", slo.getEvaluationType());
        }
        if (slo.getMetricSourceType() != null) {
            node.put("MetricSourceType", slo.getMetricSourceType());
        }
        if (slo.getSli() != null) {
            node.set("Sli", slo.getSli());
        }
        if (slo.getRequestBasedSli() != null) {
            node.set("RequestBasedSli", slo.getRequestBasedSli());
        }
        if (slo.getGoal() != null) {
            node.set("Goal", slo.getGoal());
        }
        if (slo.getBurnRateConfigurations() != null) {
            node.set("BurnRateConfigurations", slo.getBurnRateConfigurations());
        }
        if (slo.getAutoInvestigationEnabled() != null) {
            node.put("AutoInvestigationEnabled", slo.getAutoInvestigationEnabled());
        }
        return node;
    }

    private static String requireArn(JsonNode request) {
        JsonNode arn = request.get("ResourceArn");
        if (arn == null || !arn.isTextual() || arn.textValue().isBlank()) {
            throw new AwsException("ValidationException", "ResourceArn must be a string.", 400);
        }
        return arn.textValue();
    }

    private static Map<String, String> readTags(JsonNode tagsNode) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (tagsNode == null || tagsNode.isNull()) {
            throw new AwsException("ValidationException", "Tags is required.", 400);
        }
        if (!tagsNode.isArray()) {
            throw new AwsException("ValidationException", "Tags must be an array.", 400);
        }
        for (JsonNode entry : tagsNode) {
            JsonNode key = entry.get("Key");
            JsonNode value = entry.get("Value");
            if (key == null || !key.isTextual() || value == null || !value.isTextual()) {
                throw new AwsException("ValidationException", "Tags entries must have Key and Value.", 400);
            }
            tags.put(key.textValue(), value.textValue());
        }
        return tags;
    }

    private static List<String> readTagKeys(JsonNode keysNode) {
        if (keysNode == null || keysNode.isNull() || !keysNode.isArray()) {
            throw new AwsException("ValidationException", "TagKeys must be an array.", 400);
        }
        List<String> keys = new ArrayList<>(keysNode.size());
        for (JsonNode key : keysNode) {
            if (!key.isTextual()) {
                throw new AwsException("ValidationException", "TagKeys members must be strings.", 400);
            }
            keys.add(key.textValue());
        }
        return keys;
    }
}
