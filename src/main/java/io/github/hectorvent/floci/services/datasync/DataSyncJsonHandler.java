package io.github.hectorvent.floci.services.datasync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.JsonErrorResponseUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

/**
 * JSON 1.1 handler for AWS DataSync.
 * Dispatches {@code X-Amz-Target: FmrsService.<Action>} to {@link DataSyncService}.
 *
 * @see <a href="https://docs.aws.amazon.com/datasync/latest/userguide/API_Operations.html">DataSync API</a>
 */
@ApplicationScoped
public class DataSyncJsonHandler {

    private static final Logger LOG = Logger.getLogger(DataSyncJsonHandler.class);

    private final DataSyncService dataSyncService;
    private final ObjectMapper objectMapper;

    @Inject
    public DataSyncJsonHandler(DataSyncService dataSyncService, ObjectMapper objectMapper) {
        this.dataSyncService = dataSyncService;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        LOG.debugv("DataSync action: {0}", action);
        JsonNode body = request == null || request.isNull() || request.isMissingNode()
                ? objectMapper.createObjectNode()
                : request;
        try {
            return switch (action) {
                case "ListLocations" -> ok(dataSyncService.listLocations(body));
                case "CreateLocationS3" -> ok(dataSyncService.createLocationS3(body, region));
                case "DescribeLocationS3" -> ok(dataSyncService.describeLocationS3(body));
                case "CreateLocationEfs" -> ok(dataSyncService.createLocationEfs(body, region));
                case "DescribeLocationEfs" -> ok(dataSyncService.describeLocationEfs(body));
                case "DeleteLocation" -> ok(dataSyncService.deleteLocation(body));
                case "ListTasks" -> ok(dataSyncService.listTasks(body));
                case "CreateTask" -> ok(dataSyncService.createTask(body, region));
                case "DescribeTask" -> ok(dataSyncService.describeTask(body));
                case "UpdateTask" -> ok(dataSyncService.updateTask(body));
                case "DeleteTask" -> ok(dataSyncService.deleteTask(body));
                case "StartTaskExecution" -> ok(dataSyncService.startTaskExecution(body));
                case "ListTaskExecutions" -> ok(dataSyncService.listTaskExecutions(body));
                case "DescribeTaskExecution" -> ok(dataSyncService.describeTaskExecution(body));
                case "UpdateTaskExecution" -> ok(dataSyncService.updateTaskExecution(body));
                case "CancelTaskExecution" -> ok(dataSyncService.cancelTaskExecution(body));
                case "ListTagsForResource" -> ok(dataSyncService.listTagsForResource(body));
                case "TagResource" -> ok(dataSyncService.tagResource(body));
                case "UntagResource" -> ok(dataSyncService.untagResource(body));
                default -> JsonErrorResponseUtils.createUnknownOperationErrorResponse("FmrsService." + action);
            };
        } catch (AwsException e) {
            return JsonErrorResponseUtils.createErrorResponse(e);
        }
    }

    private static Response ok(Object body) {
        return Response.ok(body).build();
    }
}
