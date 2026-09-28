package io.github.hectorvent.floci.services.cur;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cur.model.ReportDefinition;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON 1.1 handler for AWS CUR operations.
 * Dispatches {@code X-Amz-Target: AWSOrigamiServiceGatewayService.*} actions to
 * {@link CurService}.
 */
@ApplicationScoped
public class CurJsonHandler {

    private static final Logger LOG = Logger.getLogger(CurJsonHandler.class);

    private final CurService service;
    private final ObjectMapper objectMapper;
    private final CurEmissionScheduler scheduler;

    @Inject
    public CurJsonHandler(CurService service, ObjectMapper objectMapper, CurEmissionScheduler scheduler) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.scheduler = scheduler;
    }

    public Response handle(String action, JsonNode request, String region) {
        LOG.debugv("CUR action: {0}", action);
        return switch (action) {
            case "PutReportDefinition" -> handlePutReportDefinition(request, region);
            case "ModifyReportDefinition" -> handleModifyReportDefinition(request, region);
            case "DescribeReportDefinitions" -> handleDescribeReportDefinitions(request);
            case "DeleteReportDefinition" -> handleDeleteReportDefinition(request, region);
            case "TagResource" -> {
                service.tagResource(stringOrNull(request, "ReportName"), region, parseTags(request.path("Tags")));
                yield Response.ok(objectMapper.createObjectNode()).build();
            }
            case "UntagResource" -> {
                service.untagResource(stringOrNull(request, "ReportName"), region,
                        parseStringList(request.path("TagKeys")));
                yield Response.ok(objectMapper.createObjectNode()).build();
            }
            case "ListTagsForResource" -> {
                ObjectNode body = objectMapper.createObjectNode();
                ArrayNode tags = body.putArray("Tags");
                service.listTagsForResource(stringOrNull(request, "ReportName"), region)
                        .entrySet().stream().sorted(Map.Entry.comparingByKey())
                        .forEach(tag -> tags.addObject().put("Key", tag.getKey()).put("Value", tag.getValue()));
                yield Response.ok(body).build();
            }
            default -> Response.status(400)
                    .entity(new AwsErrorResponse("UnknownOperationException",
                            "Unknown operation: AWSOrigamiServiceGatewayService." + action))
                    .build();
        };
    }

    private Response handlePutReportDefinition(JsonNode request, String region) {
        ReportDefinition def = parseReportDefinition(request.path("ReportDefinition"));
        def.setTags(parseTags(request.path("Tags")));
        ReportDefinition created = service.putReportDefinition(def, region);
        // Emission failures must not roll back the management mutation — match AWS
        // semantics where the definition is created even if the first run errors.
        try {
            scheduler.emitForReportSync(created, region);
        } catch (RuntimeException e) {
            LOG.warnv(e, "Synchronous emission failed for {0}; report still created.", created.getReportName());
        }
        // Re-read after emission so the response reflects the post-emit ReportStatus.
        return Response.ok(serialize(service.getReportDefinitionOrSelf(created, region))).build();
    }

    private Response handleModifyReportDefinition(JsonNode request, String region) {
        String reportName = stringOrNull(request, "ReportName");
        ReportDefinition def = parseReportDefinition(request.path("ReportDefinition"));
        ReportDefinition updated = service.modifyReportDefinition(reportName, def, region);
        try {
            scheduler.emitForReportSync(updated, region);
        } catch (RuntimeException e) {
            LOG.warnv(e, "Synchronous emission failed for {0}; report still updated.", updated.getReportName());
        }
        return Response.ok(serialize(service.getReportDefinitionOrSelf(updated, region))).build();
    }

    private Response handleDescribeReportDefinitions(JsonNode request) {
        List<ReportDefinition> defs = service.describeReportDefinitions().stream()
                .sorted(Comparator.comparing(ReportDefinition::getReportName)).toList();
        int limit = request.path("MaxResults").asInt(5);
        int start = 0;
        try {
            String token = stringOrNull(request, "NextToken");
            if (token != null) {
                start = Integer.parseInt(token);
            }
        } catch (NumberFormatException e) {
            throw new AwsException("ValidationException", "Invalid NextToken.", 400);
        }
        if (limit < 1 || limit > 5 || start < 0 || start > defs.size()) {
            throw new AwsException("ValidationException", "Invalid pagination parameters.", 400);
        }
        int end = Math.min(defs.size(), start + limit);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arr = response.putArray("ReportDefinitions");
        for (ReportDefinition d : defs.subList(start, end)) {
            arr.add(serialize(d));
        }
        if (end < defs.size()) {
            response.put("NextToken", Integer.toString(end));
        }
        return Response.ok(response).build();
    }

    private Response handleDeleteReportDefinition(JsonNode request, String region) {
        String reportName = stringOrNull(request, "ReportName");
        ReportDefinition removed = service.deleteReportDefinition(reportName, region);
        ObjectNode response = objectMapper.createObjectNode();
        if (removed != null) {
            response.put("ResponseMessage", "Report " + reportName + " has been deleted.");
        }
        return Response.ok(response).build();
    }

    private ReportDefinition parseReportDefinition(JsonNode node) {
        if (node == null || !node.isObject() || node.isEmpty()) {
            throw new AwsException("ValidationException",
                    "ReportDefinition is required.", 400);
        }
        ReportDefinition d = new ReportDefinition();
        d.setReportName(stringOrNull(node, "ReportName"));
        d.setTimeUnit(stringOrNull(node, "TimeUnit"));
        d.setFormat(stringOrNull(node, "Format"));
        d.setCompression(stringOrNull(node, "Compression"));
        d.setS3Bucket(stringOrNull(node, "S3Bucket"));
        d.setS3Prefix(stringOrNull(node, "S3Prefix"));
        d.setS3Region(stringOrNull(node, "S3Region"));
        d.setReportVersioning(stringOrNull(node, "ReportVersioning"));
        d.setBillingViewArn(stringOrNull(node, "BillingViewArn"));
        d.setRefreshClosedReports(node.path("RefreshClosedReports").asBoolean(false));
        d.setAdditionalSchemaElements(parseStringList(node.path("AdditionalSchemaElements")));
        d.setAdditionalArtifacts(parseStringList(node.path("AdditionalArtifacts")));
        return d;
    }

    private ObjectNode serialize(ReportDefinition d) {
        ObjectNode out = objectMapper.createObjectNode();
        out.put("ReportName", d.getReportName());
        out.put("TimeUnit", d.getTimeUnit());
        out.put("Format", d.getFormat());
        out.put("Compression", d.getCompression());
        out.put("S3Bucket", d.getS3Bucket());
        out.put("S3Prefix", d.getS3Prefix() == null ? "" : d.getS3Prefix());
        out.put("S3Region", d.getS3Region());
        if (d.getReportVersioning() != null) {
            out.put("ReportVersioning", d.getReportVersioning());
        }
        if (d.getBillingViewArn() != null) {
            out.put("BillingViewArn", d.getBillingViewArn());
        }
        out.put("RefreshClosedReports", d.isRefreshClosedReports());
        ArrayNode schema = out.putArray("AdditionalSchemaElements");
        if (d.getAdditionalSchemaElements() != null) {
            for (String e : d.getAdditionalSchemaElements()) {
                schema.add(e);
            }
        }
        ArrayNode artifacts = out.putArray("AdditionalArtifacts");
        if (d.getAdditionalArtifacts() != null) {
            for (String a : d.getAdditionalArtifacts()) {
                artifacts.add(a);
            }
        }
        if (d.getReportStatus() != null) {
            ObjectNode status = out.putObject("ReportStatus");
            status.put("LastStatus", d.getReportStatus());
        }
        return out;
    }

    private static Map<String, String> parseTags(JsonNode node) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (node.isArray()) {
            for (JsonNode tag : node) {
                String key = stringOrNull(tag, "Key");
                String value = stringOrNull(tag, "Value");
                if (key == null || key.isEmpty() || value == null) {
                    throw new AwsException("ValidationException", "Tags require a nonempty Key and a Value.", 400);
                }
                tags.put(key, value);
            }
        }
        return tags;
    }

    private static List<String> parseStringList(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode v : node) {
                if (!v.isNull() && !v.asText().isEmpty()) {
                    out.add(v.asText());
                }
            }
        }
        return out;
    }

    private static String stringOrNull(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return (value != null && !value.isNull()) ? value.asText() : null;
    }
}
