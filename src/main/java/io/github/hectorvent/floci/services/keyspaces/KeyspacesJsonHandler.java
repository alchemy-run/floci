package io.github.hectorvent.floci.services.keyspaces;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.JsonErrorResponseUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

/**
 * JSON 1.0 handler for Amazon Keyspaces. Dispatched from {@code AwsJsonController}
 * under the {@code KeyspacesService.} target prefix.
 *
 * @see <a href="https://docs.aws.amazon.com/keyspaces/latest/APIReference/API_Operations.html">Keyspaces API</a>
 */
@ApplicationScoped
public class KeyspacesJsonHandler {

    static final String TARGET_PREFIX = "KeyspacesService.";

    private final KeyspacesService service;
    private final ObjectMapper objectMapper;

    @Inject
    public KeyspacesJsonHandler(KeyspacesService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        JsonNode body = request == null || request.isNull() || request.isMissingNode()
                ? objectMapper.createObjectNode()
                : request;
        return switch (action) {
            case "CreateKeyspace" -> ok(service.createKeyspace(body, region));
            case "GetKeyspace" -> ok(service.getKeyspace(body));
            case "DeleteKeyspace" -> ok(service.deleteKeyspace(body));
            case "ListKeyspaces" -> ok(service.listKeyspaces());
            case "UpdateKeyspace" -> ok(service.updateKeyspace(body));
            case "CreateTable" -> ok(service.createTable(body, region));
            case "GetTable" -> ok(service.getTable(body));
            case "UpdateTable" -> ok(service.updateTable(body));
            case "DeleteTable" -> ok(service.deleteTable(body));
            case "ListTables" -> ok(service.listTables(body));
            case "RestoreTable" -> ok(service.restoreTable(body, region));
            case "CreateType" -> ok(service.createType(body));
            case "GetType" -> ok(service.getType(body));
            case "DeleteType" -> ok(service.deleteType(body));
            case "ListTypes" -> ok(service.listTypes(body));
            case "TagResource" -> ok(service.tagResource(body));
            case "UntagResource" -> ok(service.untagResource(body));
            case "ListTagsForResource" -> ok(service.listTagsForResource(body));
            default -> JsonErrorResponseUtils.createUnknownOperationErrorResponse(TARGET_PREFIX + action);
        };
    }

    private static Response ok(Object body) {
        return Response.ok(body).build();
    }
}
