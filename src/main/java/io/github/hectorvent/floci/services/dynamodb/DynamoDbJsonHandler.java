package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbOperations;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbOperations.Api;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbOperations.Call;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbOperations.Reply;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbOperations.Scope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsJsonController;
import io.github.hectorvent.floci.services.dynamodb.model.*;
import io.github.hectorvent.floci.services.kinesis.KinesisService;
import java.util.*;
import java.util.function.Supplier;

/**
 * The public DynamoDB entry point used by the JSON and CBOR controllers, API Gateway AWS
 * integrations, AppSync and Step Functions. It resolves the caller's account and region and
 * dispatches through the selected backend. CRC32, JSON 1.0 and CBOR conversion stay in the controllers.
 */
@ApplicationScoped
public class DynamoDbJsonHandler {

    private final DynamoDbOperations operations;
    private final RegionResolver regionResolver;

    @Inject
    public DynamoDbJsonHandler(DynamoDbOperations operations, RegionResolver regionResolver,
            ObjectMapper objectMapper) {
        this.operations = operations;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) throws Exception {
        Scope scope = new Scope(regionResolver.getAccountId(),
                region != null ? region : regionResolver.getDefaultRegion());
        return toResponse(operations.execute(new Call(scope, Api.DYNAMODB, action, request)));
    }

    public Response handleLegacy(String action, JsonNode request, String region) throws Exception {
        Scope scope = new Scope(regionResolver.getAccountId(),
                region != null ? region : regionResolver.getDefaultRegion());
        return toResponse(new DynamoDbLegacyProtocol(operations, scope).execute(action, request));
    }

    static Response toResponse(Reply reply) {
        Response.ResponseBuilder builder = Response.status(reply.status()).entity(reply.body());
        for (Map.Entry<String, List<String>> header : reply.headers().entrySet()) {
            for (String value : header.getValue()) {
                builder.header(header.getKey(), value);
            }
        }
        return builder.build();
    }







    private final ObjectMapper objectMapper;
















































































    private ObjectNode writeCapacityNode(String tableName, DynamoDbWriteCapacity.Cost cost,
                                          boolean withBreakdown) {
        return writeCapacityNode(tableName, cost, withBreakdown, null);
    }

    private ObjectNode writeCapacityNode(String tableName, DynamoDbWriteCapacity.Cost cost,
                                          boolean withBreakdown, String unitsField) {
        ObjectNode cc = objectMapper.createObjectNode();
        cc.put("TableName", DynamoDbTableNames.resolve(tableName));
        cc.setAll(capacityUnitsNode(cost.total(), unitsField));
        if (withBreakdown) {
            cc.set("Table", capacityUnitsNode(cost.table(), unitsField));
            if (!cost.gsi().isEmpty()) {
                cc.set("GlobalSecondaryIndexes", capacityUnitsMap(cost.gsi(), unitsField));
            }
            if (!cost.lsi().isEmpty()) {
                cc.set("LocalSecondaryIndexes", capacityUnitsMap(cost.lsi(), unitsField));
            }
        }
        return cc;
    }

    private ObjectNode capacityUnitsMap(Map<String, Double> unitsByIndex, String unitsField) {
        ObjectNode node = objectMapper.createObjectNode();
        unitsByIndex.forEach((indexName, units) -> node.set(indexName, capacityUnitsNode(units, unitsField)));
        return node;
    }

    private ObjectNode capacityUnitsNode(double units, String unitsField) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("CapacityUnits", units);
        if (unitsField != null) {
            node.put(unitsField, units);
        }
        return node;
    }


































}
