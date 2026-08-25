package io.github.hectorvent.floci.services.dms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

/**
 * JSON 1.1 handler for AWS Database Migration Service.
 * Dispatches {@code X-Amz-Target: AmazonDMSv20160101.<Action>} to {@link DmsService}.
 *
 * @see <a href="https://docs.aws.amazon.com/dms/latest/APIReference/Welcome.html">DMS API</a>
 */
@ApplicationScoped
public class DmsJsonHandler {

    private static final Logger LOG = Logger.getLogger(DmsJsonHandler.class);

    private final DmsService dmsService;
    private final ObjectMapper objectMapper;

    @Inject
    public DmsJsonHandler(DmsService dmsService, ObjectMapper objectMapper) {
        this.dmsService = dmsService;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        LOG.debugv("DMS action: {0}", action);
        JsonNode body = request == null || request.isNull() || request.isMissingNode()
                ? objectMapper.createObjectNode()
                : request;
        return switch (action) {
            case "CreateEndpoint" -> ok(dmsService.createEndpoint(body, region));
            case "ModifyEndpoint" -> ok(dmsService.modifyEndpoint(body));
            case "DeleteEndpoint" -> ok(dmsService.deleteEndpoint(body));
            case "DescribeEndpoints" -> ok(dmsService.describeEndpoints(body));
            case "ListTagsForResource" -> ok(dmsService.listTagsForResource(body));
            case "AddTagsToResource" -> ok(dmsService.addTagsToResource(body));
            case "RemoveTagsFromResource" -> ok(dmsService.removeTagsFromResource(body));
            case "DescribeSchemas" -> ok(dmsService.describeSchemas(body));
            case "DescribeRefreshSchemasStatus" -> ok(dmsService.describeRefreshSchemasStatus(body));
            case "DescribeConnections" -> ok(dmsService.describeConnections(body));
            case "DescribeEvents" -> ok(dmsService.describeEvents(body));
            case "DescribeEndpointSettings" -> ok(dmsService.describeEndpointSettings(body));
            case "DescribeOrderableReplicationInstances" ->
                    ok(dmsService.describeOrderableReplicationInstances(body));
            case "DescribeReplicationTasks" -> ok(dmsService.describeReplicationTasks(body));
            case "DescribeReplications" -> ok(dmsService.describeReplications(body));
            case "StartReplicationTask" -> ok(dmsService.startReplicationTask(body));
            case "StopReplicationTask" -> ok(dmsService.stopReplicationTask(body));
            case "DescribeTableStatistics" -> ok(dmsService.describeTableStatistics(body));
            case "ReloadTables" -> ok(dmsService.reloadTables(body));
            case "StartReplication" -> ok(dmsService.startReplication(body));
            case "StopReplication" -> ok(dmsService.stopReplication(body));
            default -> Response.status(400)
                    .entity(new AwsErrorResponse("UnknownOperationException",
                            "Unknown operation: AmazonDMSv20160101." + action))
                    .build();
        };
    }

    private static Response ok(Object body) {
        return Response.ok(body).build();
    }
}
