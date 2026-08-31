package io.github.hectorvent.floci.services.timestreaminfluxdb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.JsonErrorResponseUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.Map;

/**
 * JSON 1.0 handler for Amazon Timestream for InfluxDB. Dispatched from
 * {@code AwsJsonController} under {@code AmazonTimestreamInfluxDB.}.
 */
@ApplicationScoped
public class TimestreamInfluxDbJsonHandler {

    static final String TARGET_PREFIX = "AmazonTimestreamInfluxDB.";

    private final TimestreamInfluxDbService service;
    private final ObjectMapper objectMapper;

    @Inject
    public TimestreamInfluxDbJsonHandler(TimestreamInfluxDbService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        JsonNode body = request == null || request.isNull() || request.isMissingNode()
                ? objectMapper.createObjectNode()
                : request;
        try {
            return switch (action) {
                case "CreateDbInstance" -> ok(service.createDbInstance(body, region));
                case "GetDbInstance" -> ok(service.getDbInstance(body));
                case "UpdateDbInstance" -> ok(service.updateDbInstance(body));
                case "DeleteDbInstance" -> ok(service.deleteDbInstance(body));
                case "ListDbInstances" -> ok(service.listDbInstances(body));
                case "TagResource" -> ok(service.tagResource(body));
                case "UntagResource" -> ok(service.untagResource(body));
                case "ListTagsForResource" -> ok(service.listTagsForResource(body));
                default -> JsonErrorResponseUtils.createUnknownOperationErrorResponse(TARGET_PREFIX + action);
            };
        } catch (AwsException e) {
            return error(e);
        }
    }

    private static Response ok(Object body) {
        return Response.ok(body).build();
    }

    private Response error(AwsException e) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("__type", e.jsonType());
        if (e.getMessage() != null) {
            body.put("message", e.getMessage());
        }
        Map<String, Object> extra = e.getExtendedData();
        if (extra != null) {
            extra.forEach((key, value) -> {
                if (value instanceof String s) {
                    body.put(key, s);
                }
            });
        }
        return Response.status(e.getHttpStatus()).entity(body).build();
    }
}
