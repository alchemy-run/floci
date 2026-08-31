package io.github.hectorvent.floci.services.fsx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.JsonErrorResponseUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

/**
 * JSON 1.1 handler for Amazon FSx. Dispatched from {@code AwsJson11Controller}
 * under the {@code AWSSimbaAPIService_v20180301.} target prefix.
 */
@ApplicationScoped
public class FsxJsonHandler {

    static final String TARGET_PREFIX = "AWSSimbaAPIService_v20180301.";

    private final FsxService service;
    private final ObjectMapper objectMapper;

    @Inject
    public FsxJsonHandler(FsxService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        JsonNode body = request == null || request.isNull() || request.isMissingNode()
                ? objectMapper.createObjectNode()
                : request;
        try {
            return switch (action) {
                case "CreateFileSystem" -> ok(service.createFileSystem(body, region));
                case "DescribeFileSystems" -> ok(service.describeFileSystems(body, region));
                case "UpdateFileSystem" -> ok(service.updateFileSystem(body, region));
                case "DeleteFileSystem" -> ok(service.deleteFileSystem(body, region));
                case "DescribeBackups" -> ok(service.describeBackups(body, region));
                case "DeleteBackup" -> ok(service.deleteBackup(body, region));
                case "CopyBackup" -> ok(service.copyBackup(body, region));
                case "DescribeSnapshots" -> ok(service.describeSnapshots(body, region));
                case "CreateSnapshot" -> ok(service.createSnapshot(body, region));
                case "UpdateSnapshot" -> ok(service.updateSnapshot(body, region));
                case "DeleteSnapshot" -> ok(service.deleteSnapshot(body, region));
                case "DescribeVolumes" -> ok(service.describeVolumes(body, region));
                case "RestoreVolumeFromSnapshot" -> ok(service.restoreVolumeFromSnapshot(body, region));
                case "CopySnapshotAndUpdateVolume" -> ok(service.copySnapshotAndUpdateVolume(body, region));
                case "DescribeStorageVirtualMachines" ->
                        ok(service.describeStorageVirtualMachines(body, region));
                case "DescribeDataRepositoryTasks" -> ok(service.describeDataRepositoryTasks(body, region));
                case "DescribeDataRepositoryAssociations" ->
                        ok(service.describeDataRepositoryAssociations(body, region));
                case "CancelDataRepositoryTask" -> ok(service.cancelDataRepositoryTask(body, region));
                case "TagResource" -> ok(service.tagResource(body, region));
                case "UntagResource" -> ok(service.untagResource(body, region));
                case "ListTagsForResource" -> ok(service.listTagsForResource(body, region));
                default -> JsonErrorResponseUtils.createUnknownOperationErrorResponse(TARGET_PREFIX + action);
            };
        } catch (AwsException e) {
            return JsonErrorResponseUtils.createErrorResponse(e);
        }
    }

    private static Response ok(Object body) {
        return Response.ok(body).build();
    }
}
