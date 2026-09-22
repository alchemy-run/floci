package io.github.hectorvent.floci.services.bcmdataexports;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.bcmdataexports.model.DataQuery;
import io.github.hectorvent.floci.services.bcmdataexports.model.DestinationConfiguration;
import io.github.hectorvent.floci.services.bcmdataexports.model.Export;
import io.github.hectorvent.floci.services.bcmdataexports.model.ExportExecution;
import io.github.hectorvent.floci.services.bcmdataexports.model.RefreshCadence;
import io.github.hectorvent.floci.services.cur.CurEmissionScheduler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON 1.1 handler for AWS BCM Data Exports operations.
 * Dispatches {@code X-Amz-Target: AWSBillingAndCostManagementDataExports.*}
 * actions to {@link BcmDataExportsService}.
 */
@ApplicationScoped
public class BcmDataExportsJsonHandler {

    private static final Logger LOG = Logger.getLogger(BcmDataExportsJsonHandler.class);

    private final BcmDataExportsService service;
    private final ObjectMapper objectMapper;
    private final CurEmissionScheduler scheduler;

    @Inject
    public BcmDataExportsJsonHandler(BcmDataExportsService service,
                                     ObjectMapper objectMapper,
                                     CurEmissionScheduler scheduler) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.scheduler = scheduler;
    }

    public Response handle(String action, JsonNode request, String region) {
        LOG.debugv("BcmDataExports action: {0}", action);
        return switch (action) {
            case "CreateExport" -> handleCreate(request, region);
            case "GetExport" -> handleGet(request);
            case "ListExports" -> handleList(request);
            case "UpdateExport" -> handleUpdate(request);
            case "DeleteExport" -> handleDelete(request);
            case "ListExecutions" -> handleListExecutions(request);
            case "GetExecution" -> handleGetExecution(request);
            case "ListTagsForResource" -> handleListTags(request);
            case "TagResource" -> {
                service.tagResource(stringOrNull(request, "ResourceArn"),
                        parseResourceTags(request.path("ResourceTags")));
                yield Response.ok(objectMapper.createObjectNode()).build();
            }
            case "UntagResource" -> {
                List<String> keys = new ArrayList<>();
                request.path("ResourceTagKeys").forEach(key -> keys.add(key.asText()));
                service.untagResource(stringOrNull(request, "ResourceArn"), keys);
                yield Response.ok(objectMapper.createObjectNode()).build();
            }
            case "ListTables" -> handleListTables(request);
            case "GetTable" -> handleGetTable(request);
            default -> Response.status(400)
                    .entity(new AwsErrorResponse("UnknownOperationException",
                            "Unknown operation: AWSBillingAndCostManagementDataExports." + action))
                    .build();
        };
    }

    private Response handleCreate(JsonNode request, String region) {
        Export incoming = parseExport(request.path("Export"));
        Map<String, String> tags = parseResourceTags(request.path("ResourceTags"));
        Export created = service.createExport(incoming, tags, region);
        try {
            scheduler.emitForExportSync(created, region, "USER");
        } catch (RuntimeException e) {
            LOG.warnv(e, "Synchronous emission failed for {0}; export still created.", created.getName());
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.put("ExportArn", created.getExportArn());
        return Response.ok(response).build();
    }

    private Response handleGet(JsonNode request) {
        String arn = stringOrNull(request, "ExportArn");
        Export export = service.getExport(arn);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Export", serializeExport(export));
        response.set("ExportStatus", serializeExportStatus(export));
        return Response.ok(response).build();
    }

    private Response handleList(JsonNode request) {
        List<ObjectNode> exports = service.listExports().stream()
                .sorted(Comparator.comparing(Export::getExportArn)).map(this::serializeExportReference).toList();
        return page(request, objectMapper.createObjectNode(), "Exports", exports);
    }

    private Response handleUpdate(JsonNode request) {
        String arn = stringOrNull(request, "ExportArn");
        Export incoming = parseExport(request.path("Export"));
        Export updated = service.updateExport(arn, incoming);
        try {
            scheduler.emitForExportSync(updated, null, "USER");
        } catch (RuntimeException e) {
            LOG.warnv(e, "Synchronous emission failed for {0}; export still updated.", updated.getExportArn());
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.put("ExportArn", updated.getExportArn());
        return Response.ok(response).build();
    }

    private Response handleDelete(JsonNode request) {
        String arn = stringOrNull(request, "ExportArn");
        service.deleteExport(arn);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("ExportArn", arn == null ? "" : arn);
        return Response.ok(response).build();
    }

    private Response handleListExecutions(JsonNode request) {
        String arn = stringOrNull(request, "ExportArn");
        List<ObjectNode> executions = service.listExecutions(arn).stream()
                .sorted(Comparator.comparingLong(ExportExecution::getCreatedAt).reversed()
                        .thenComparing(ExportExecution::getExecutionId))
                .map(this::serializeExecution).toList();
        return page(request, objectMapper.createObjectNode(), "Executions", executions);
    }

    private Response handleGetExecution(JsonNode request) {
        String arn = stringOrNull(request, "ExportArn");
        String executionId = stringOrNull(request, "ExecutionId");
        ExportExecution exec = service.getExecution(arn, executionId);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("ExecutionId", exec.getExecutionId());
        response.set("Export", serializeExport(exec.getExport() != null ? exec.getExport() : service.getExport(arn)));
        response.set("ExecutionStatus", serializeExecutionStatus(exec));
        return Response.ok(response).build();
    }

    private Response handleListTags(JsonNode request) {
        ObjectNode response = objectMapper.createObjectNode();
        List<ObjectNode> tags = service.listTagsForResource(stringOrNull(request, "ResourceArn"))
                .entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(tag -> objectMapper.createObjectNode().put("Key", tag.getKey()).put("Value", tag.getValue()))
                .toList();
        return page(request, response, "ResourceTags", tags);
    }

    // Core CUR 2.0 metadata; this catalog does not enumerate every AWS billing column.
    private static final String CUR_TABLE = "COST_AND_USAGE_REPORT";
    private static final Map<String, List<String>> TABLE_PROPERTIES = Map.of(
            "TIME_GRANULARITY", List.of("HOURLY", "DAILY", "MONTHLY"),
            "INCLUDE_RESOURCES", List.of("FALSE", "TRUE"),
            "INCLUDE_MANUAL_DISCOUNT_COMPATIBILITY", List.of("FALSE", "TRUE"),
            "INCLUDE_SPLIT_COST_ALLOCATION_DATA", List.of("FALSE", "TRUE"));

    private Response handleListTables(JsonNode request) {
        ObjectNode table = objectMapper.createObjectNode();
        table.put("TableName", CUR_TABLE);
        table.put("Description", "Cost and usage report (CUR 2.0).");
        ArrayNode properties = table.putArray("TableProperties");
        TABLE_PROPERTIES.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            ObjectNode property = properties.addObject();
            property.put("Name", entry.getKey());
            property.put("DefaultValue", entry.getValue().getFirst());
            ArrayNode values = property.putArray("ValidValues");
            entry.getValue().forEach(values::add);
        });
        return page(request, objectMapper.createObjectNode(), "Tables", List.of(table));
    }

    private Response handleGetTable(JsonNode request) {
        if (!CUR_TABLE.equals(stringOrNull(request, "TableName"))) {
            throw new AwsException("ValidationException", "Unknown table: " + stringOrNull(request, "TableName"), 400);
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.put("TableName", CUR_TABLE);
        response.put("Description", "Cost and usage report (CUR 2.0).");
        ObjectNode properties = response.putObject("TableProperties");
        TABLE_PROPERTIES.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> properties.put(entry.getKey(), entry.getValue().getFirst()));
        Iterator<Map.Entry<String, JsonNode>> requested = request.path("TableProperties").fields();
        while (requested.hasNext()) {
            Map.Entry<String, JsonNode> entry = requested.next();
            List<String> allowed = TABLE_PROPERTIES.get(entry.getKey());
            if (allowed == null || !allowed.contains(entry.getValue().asText())) {
                throw new AwsException("ValidationException", "Invalid table property: " + entry.getKey(), 400);
            }
            properties.put(entry.getKey(), entry.getValue().asText());
        }
        ArrayNode schema = response.putArray("Schema");
        for (String name : List.of("identity_line_item_id", "identity_time_interval", "bill_bill_type",
                "bill_billing_entity", "bill_invoice_id", "bill_invoicing_entity", "bill_payer_account_id",
                "line_item_availability_zone", "line_item_currency_code", "line_item_legal_entity",
                "line_item_line_item_description", "line_item_line_item_type", "line_item_operation",
                "line_item_product_code", "line_item_tax_type", "line_item_usage_account_id", "line_item_usage_type",
                "pricing_currency", "pricing_lease_contract_length", "pricing_offering_class",
                "pricing_purchase_option", "pricing_rate_code", "pricing_rate_id", "pricing_term", "pricing_unit")) {
            schema.addObject().put("Name", name).put("Type", "STRING");
        }
        for (String name : List.of("bill_billing_period_start_date", "bill_billing_period_end_date",
                "line_item_usage_start_date", "line_item_usage_end_date")) {
            schema.addObject().put("Name", name).put("Type", "TIMESTAMP");
        }
        for (String name : List.of("line_item_blended_cost", "line_item_blended_rate", "line_item_unblended_cost",
                "line_item_unblended_rate", "line_item_usage_amount", "line_item_normalization_factor",
                "line_item_normalized_usage_amount", "pricing_public_on_demand_cost", "pricing_public_on_demand_rate")) {
            schema.addObject().put("Name", name).put("Type", "DOUBLE");
        }
        for (String name : List.of("product", "discount", "resource_tags", "cost_category")) {
            schema.addObject().put("Name", name).put("Type", "MAP");
        }
        if ("TRUE".equals(properties.path("INCLUDE_RESOURCES").asText())) {
            schema.addObject().put("Name", "line_item_resource_id").put("Type", "STRING");
        }
        if ("TRUE".equals(properties.path("INCLUDE_SPLIT_COST_ALLOCATION_DATA").asText())) {
            schema.addObject().put("Name", "split_line_item_parent_resource_id").put("Type", "STRING");
            for (String name : List.of("split_line_item_actual_usage", "split_line_item_reserved_usage",
                    "split_line_item_split_usage", "split_line_item_split_usage_ratio", "split_line_item_split_cost",
                    "split_line_item_unused_cost", "split_line_item_public_on_demand_split_cost",
                    "split_line_item_public_on_demand_unused_cost")) {
                schema.addObject().put("Name", name).put("Type", "DOUBLE");
            }
        }
        return Response.ok(response).build();
    }

    private Response page(JsonNode request, ObjectNode response, String field, List<ObjectNode> values) {
        int limit = request.path("MaxResults").asInt(100);
        int start = 0;
        try {
            String token = stringOrNull(request, "NextToken");
            if (token != null) {
                start = Integer.parseInt(token);
            }
        } catch (NumberFormatException e) {
            throw new AwsException("ValidationException", "Invalid NextToken.", 400);
        }
        if (limit < 1 || limit > 100 || start < 0 || start > values.size()) {
            throw new AwsException("ValidationException", "Invalid pagination parameters.", 400);
        }
        int end = Math.min(values.size(), start + limit);
        ArrayNode items = response.putArray(field);
        values.subList(start, end).forEach(items::add);
        if (end < values.size()) {
            response.put("NextToken", Integer.toString(end));
        }
        return Response.ok(response).build();
    }

    private Export parseExport(JsonNode node) {
        if (node == null || !node.isObject() || node.isEmpty()) {
            throw new AwsException("ValidationException", "Export is required.", 400);
        }
        Export e = new Export();
        e.setName(stringOrNull(node, "Name"));
        e.setDescription(stringOrNull(node, "Description"));
        e.setDataQuery(parseDataQuery(node.path("DataQuery")));
        e.setDestinationConfigurations(parseDestination(node.path("DestinationConfigurations")));
        e.setRefreshCadence(parseRefreshCadence(node.path("RefreshCadence")));
        return e;
    }

    private DataQuery parseDataQuery(JsonNode node) {
        if (!node.isObject() || node.isEmpty()) {
            return null;
        }
        DataQuery dq = new DataQuery();
        dq.setQueryStatement(stringOrNull(node, "QueryStatement"));
        JsonNode tableConfigs = node.path("TableConfigurations");
        if (tableConfigs.isObject()) {
            Map<String, Map<String, String>> result = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> tables = tableConfigs.fields();
            while (tables.hasNext()) {
                Map.Entry<String, JsonNode> table = tables.next();
                Map<String, String> props = new LinkedHashMap<>();
                Iterator<Map.Entry<String, JsonNode>> propIter = table.getValue().fields();
                while (propIter.hasNext()) {
                    Map.Entry<String, JsonNode> prop = propIter.next();
                    props.put(prop.getKey(), prop.getValue().asText());
                }
                result.put(table.getKey(), props);
            }
            dq.setTableConfigurations(result);
        }
        return dq;
    }

    private DestinationConfiguration parseDestination(JsonNode node) {
        if (!node.isObject() || node.isEmpty()) {
            return null;
        }
        DestinationConfiguration dest = new DestinationConfiguration();
        JsonNode s3Node = node.path("S3Destination");
        if (s3Node.isObject() && !s3Node.isEmpty()) {
            DestinationConfiguration.S3Destination s3 = new DestinationConfiguration.S3Destination();
            s3.setS3Bucket(stringOrNull(s3Node, "S3Bucket"));
            s3.setS3Prefix(stringOrNull(s3Node, "S3Prefix"));
            s3.setS3Region(stringOrNull(s3Node, "S3Region"));
            s3.setS3BucketOwner(stringOrNull(s3Node, "S3BucketOwner"));
            JsonNode outNode = s3Node.path("S3OutputConfigurations");
            if (outNode.isObject() && !outNode.isEmpty()) {
                DestinationConfiguration.S3OutputConfigurations out = new DestinationConfiguration.S3OutputConfigurations();
                out.setCompression(stringOrNull(outNode, "Compression"));
                out.setFormat(stringOrNull(outNode, "Format"));
                out.setOutputType(stringOrNull(outNode, "OutputType"));
                out.setOverwrite(stringOrNull(outNode, "Overwrite"));
                s3.setS3OutputConfigurations(out);
            }
            dest.setS3Destination(s3);
        }
        return dest;
    }

    private RefreshCadence parseRefreshCadence(JsonNode node) {
        if (!node.isObject() || node.isEmpty()) {
            return null;
        }
        RefreshCadence cadence = new RefreshCadence();
        cadence.setFrequency(stringOrNull(node, "Frequency"));
        return cadence;
    }

    private Map<String, String> parseResourceTags(JsonNode node) {
        Map<String, String> out = new HashMap<>();
        if (node.isArray()) {
            for (JsonNode entry : node) {
                String key = stringOrNull(entry, "Key");
                String value = stringOrNull(entry, "Value");
                if (key == null || key.isEmpty() || value == null) {
                    throw new AwsException("ValidationException", "ResourceTags require a nonempty Key and a Value.", 400);
                }
                out.put(key, value);
            }
        }
        return out;
    }

    private ObjectNode serializeExport(Export e) {
        ObjectNode out = objectMapper.createObjectNode();
        out.put("ExportArn", e.getExportArn());
        out.put("Name", e.getName());
        if (e.getDescription() != null) {
            out.put("Description", e.getDescription());
        }
        if (e.getDataQuery() != null) {
            ObjectNode dq = out.putObject("DataQuery");
            dq.put("QueryStatement", e.getDataQuery().getQueryStatement());
            if (e.getDataQuery().getTableConfigurations() != null
                    && !e.getDataQuery().getTableConfigurations().isEmpty()) {
                ObjectNode tableConfigs = dq.putObject("TableConfigurations");
                for (Map.Entry<String, Map<String, String>> table : e.getDataQuery().getTableConfigurations().entrySet()) {
                    ObjectNode props = tableConfigs.putObject(table.getKey());
                    for (Map.Entry<String, String> prop : table.getValue().entrySet()) {
                        props.put(prop.getKey(), prop.getValue());
                    }
                }
            }
        }
        if (e.getDestinationConfigurations() != null
                && e.getDestinationConfigurations().getS3Destination() != null) {
            ObjectNode dest = out.putObject("DestinationConfigurations");
            ObjectNode s3 = dest.putObject("S3Destination");
            DestinationConfiguration.S3Destination src = e.getDestinationConfigurations().getS3Destination();
            s3.put("S3Bucket", src.getS3Bucket());
            s3.put("S3Prefix", src.getS3Prefix() == null ? "" : src.getS3Prefix());
            s3.put("S3Region", src.getS3Region());
            if (src.getS3BucketOwner() != null) {
                s3.put("S3BucketOwner", src.getS3BucketOwner());
            }
            DestinationConfiguration.S3OutputConfigurations outCfg = src.getS3OutputConfigurations();
            if (outCfg != null) {
                ObjectNode outNode = s3.putObject("S3OutputConfigurations");
                if (outCfg.getCompression() != null) outNode.put("Compression", outCfg.getCompression());
                if (outCfg.getFormat() != null) outNode.put("Format", outCfg.getFormat());
                if (outCfg.getOutputType() != null) outNode.put("OutputType", outCfg.getOutputType());
                if (outCfg.getOverwrite() != null) outNode.put("Overwrite", outCfg.getOverwrite());
            }
        }
        if (e.getRefreshCadence() != null) {
            ObjectNode cadence = out.putObject("RefreshCadence");
            cadence.put("Frequency", e.getRefreshCadence().getFrequency());
        }
        return out;
    }

    private ObjectNode serializeExportStatus(Export e) {
        ObjectNode status = objectMapper.createObjectNode();
        if (e.getExportStatus() != null) {
            status.put("StatusCode", e.getExportStatus());
        }
        if (e.getCreatedAt() > 0) {
            status.put("CreatedAt", Instant.ofEpochMilli(e.getCreatedAt()).toString());
        }
        if (e.getLastUpdatedAt() > 0) {
            status.put("LastUpdatedAt", Instant.ofEpochMilli(e.getLastUpdatedAt()).toString());
        }
        return status;
    }

    private ObjectNode serializeExportReference(Export e) {
        ObjectNode out = objectMapper.createObjectNode();
        out.put("ExportArn", e.getExportArn());
        out.put("ExportName", e.getName());
        if (e.getExportStatus() != null) {
            ObjectNode status = out.putObject("ExportStatus");
            status.put("StatusCode", e.getExportStatus());
        }
        return out;
    }

    private ObjectNode serializeExecution(ExportExecution exec) {
        ObjectNode out = objectMapper.createObjectNode();
        out.put("ExecutionId", exec.getExecutionId());
        out.set("ExecutionStatus", serializeExecutionStatus(exec));
        return out;
    }

    private ObjectNode serializeExecutionStatus(ExportExecution exec) {
        ObjectNode status = objectMapper.createObjectNode();
        status.put("StatusCode", exec.getExportStatus());
        if (exec.getCreatedAt() > 0) {
            status.put("CreatedAt", Instant.ofEpochMilli(exec.getCreatedAt()).toString());
        }
        if (exec.getCompletedAt() > 0) {
            status.put("CompletedAt", Instant.ofEpochMilli(exec.getCompletedAt()).toString());
        }
        long updated = exec.getCompletedAt() > 0 ? exec.getCompletedAt() : exec.getCreatedAt();
        if (updated > 0) {
            status.put("LastUpdatedAt", Instant.ofEpochMilli(updated).toString());
        }
        if (exec.getStatusReason() != null) {
            status.put("StatusReason", "INTERNAL_FAILURE");
        }
        return status;
    }

    private static String stringOrNull(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return (value != null && !value.isNull()) ? value.asText() : null;
    }
}
