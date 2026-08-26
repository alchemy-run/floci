package io.github.hectorvent.floci.services.timestream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.JsonErrorResponseUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JSON 1.0 handler for Amazon Timestream write + query. Dispatched from
 * {@code AwsJsonController} under {@code Timestream_20181101.}.
 */
@ApplicationScoped
public class TimestreamJsonHandler {

    static final String TARGET_PREFIX = "Timestream_20181101.";

    private final TimestreamService service;
    private final ObjectMapper objectMapper;

    @Inject
    public TimestreamJsonHandler(TimestreamService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region, String host) {
        JsonNode body = request == null || request.isNull() || request.isMissingNode()
                ? objectMapper.createObjectNode()
                : request;
        try {
            return switch (action) {
            case "DescribeEndpoints" -> ok(service.describeEndpoints(host));
            case "CreateDatabase" -> ok(service.createDatabase(body, region));
            case "DescribeDatabase" -> ok(service.describeDatabase(body));
            case "UpdateDatabase" -> ok(service.updateDatabase(body));
            case "DeleteDatabase" -> ok(service.deleteDatabase(body));
            case "ListDatabases" -> ok(service.listDatabases());
            case "CreateTable" -> ok(service.createTable(body, region));
            case "DescribeTable" -> ok(service.describeTable(body));
            case "UpdateTable" -> ok(service.updateTable(body));
            case "DeleteTable" -> ok(service.deleteTable(body));
            case "ListTables" -> ok(service.listTables(body));
            case "WriteRecords" -> ok(service.writeRecords(body));
            case "Query" -> ok(service.query(body));
            case "PrepareQuery" -> ok(service.prepareQuery(body));
            case "CancelQuery" -> ok(service.cancelQuery(body));
            case "CreateScheduledQuery" -> ok(service.createScheduledQuery(body, region));
            case "DescribeScheduledQuery" -> ok(service.describeScheduledQuery(body));
            case "ListScheduledQueries" -> ok(service.listScheduledQueries());
            case "UpdateScheduledQuery" -> ok(service.updateScheduledQuery(body));
            case "DeleteScheduledQuery" -> ok(service.deleteScheduledQuery(body));
            case "ExecuteScheduledQuery" -> ok(service.executeScheduledQuery(body));
            case "TagResource" -> ok(service.tagResource(body));
            case "UntagResource" -> ok(service.untagResource(body));
            case "ListTagsForResource" -> ok(service.listTagsForResource(body));
            default -> JsonErrorResponseUtils.createUnknownOperationErrorResponse(TARGET_PREFIX + action);
            };
        } catch (AwsException e) {
            if (e.getExtendedData() == null) {
                throw e;
            }
            Map<String, Object> entity = new LinkedHashMap<>();
            entity.put("__type", e.jsonType());
            entity.put("message", e.getMessage());
            entity.putAll(e.getExtendedData());
            return Response.status(e.getHttpStatus()).entity(entity).build();
        }
    }

    private static Response ok(Object body) {
        return Response.ok(body).build();
    }
}
