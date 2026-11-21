package io.floci.aws.services.keyspaces;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsErrorResponse;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestConstraintCheck;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Set;

/**
 * Dispatch for Amazon Keyspaces ({@code KeyspacesService.<Action>} over JSON 1.0 or Smithy RPC v2 CBOR) and Amazon
 * Keyspaces Streams ({@code KeyspacesStreams.<Action>}).
 */
@ApplicationScoped
public class KeyspacesJsonHandler {

    private static final RequestConstraintCheck SHAPES = shapes("aws/keyspaces-request-shapes.json", "Keyspaces");
    private static final RequestConstraintCheck STREAM_SHAPES = shapes("aws/keyspacesstreams-request-shapes.json",
            "Keyspaces Streams");

    private static RequestConstraintCheck shapes(String resource, String name) {
        return RequestConstraintCheck.load(resource, name + " request shapes", new RequestConstraintCheck.Messages() {
                @Override
                public AwsException type(String path, String type) {
                    return violation(path, "Member must be a " + type);
                }

                @Override
                public AwsException bounds(String path, String type, Long min, Long max, long size) {
                    String what = "string".equals(type) ? "length" : Set.of("integer", "long", "float", "double")
                            .contains(type) ? "value" : "size";
                    return violation(path, min != null && size < min
                            ? "Member must have " + what + " greater than or equal to " + min
                            : "Member must have " + what + " less than or equal to " + max);
                }

                @Override
                public AwsException pattern(String path, String pattern) {
                    return violation(path, "Member must satisfy regular expression pattern: " + pattern);
                }

                @Override
                public AwsException enumValue(String path, List<String> values) {
                    return violation(path, "Member must satisfy enum value set: [" + String.join(", ", values)
                            + "]");
                }

                @Override
                public AwsException required(String path) {
                    return violation(path, "Member must not be null");
                }
            });
    }

    private final KeyspacesService service;
    private final KeyspacesStreams streams;

    @Inject
    public KeyspacesJsonHandler(KeyspacesService service, KeyspacesStreams streams) {
        this.service = service;
        this.streams = streams;
    }

    private static AwsException violation(String path, String constraint) {
        String member = path.contains(".") ? path.substring(path.indexOf('.') + 1) : path;
        return KeyspacesStore.validation("1 validation error detected: Value at '" + member
                + "' failed to satisfy constraint: " + constraint);
    }

    public Response handle(String action, JsonNode request, String region) {
        SHAPES.check(action, request);
        ObjectNode result = switch (action) {
            case "CreateKeyspace" -> service.createKeyspace(request, region);
            case "CreateTable" -> service.createTable(request, region);
            case "CreateType" -> service.createType(request, region);
            case "DeleteKeyspace" -> service.deleteKeyspace(request, region);
            case "DeleteTable" -> service.deleteTable(request, region);
            case "DeleteType" -> service.deleteType(request, region);
            case "GetKeyspace" -> service.getKeyspace(request, region);
            case "GetTable" -> service.getTable(request, region);
            case "GetTableAutoScalingSettings" -> service.getTableAutoScalingSettings(request, region);
            case "GetType" -> service.getType(request, region);
            case "ListKeyspaces" -> service.listKeyspaces(request, region);
            case "ListTables" -> service.listTables(request, region);
            case "ListTagsForResource" -> service.listTagsForResource(request, region);
            case "ListTypes" -> service.listTypes(request, region);
            case "RestoreTable" -> service.restoreTable(request, region);
            case "TagResource" -> service.tagResource(request, region);
            case "UntagResource" -> service.untagResource(request, region);
            case "UpdateKeyspace" -> service.updateKeyspace(request, region);
            case "UpdateTable" -> service.updateTable(request, region);
            default -> null;
        };
        return respond(result, "KeyspacesService." + action);
    }

    public Response handleStreams(String action, JsonNode request, String region) {
        STREAM_SHAPES.check(action, request);
        ObjectNode result = switch (action) {
            case "GetRecords" -> streams.getRecords(request, region);
            case "GetShardIterator" -> streams.getShardIterator(request, region);
            case "GetStream" -> streams.getStream(request, region);
            case "ListStreams" -> streams.listStreams(request, region);
            default -> null;
        };
        return respond(result, "KeyspacesStreams." + action);
    }

    private static Response respond(ObjectNode result, String target) {
        if (result == null) {
            return Response.status(400).entity(new AwsErrorResponse("UnknownOperationException",
                    "Unknown operation: " + target)).build();
        }
        return Response.ok(result).build();
    }
}
