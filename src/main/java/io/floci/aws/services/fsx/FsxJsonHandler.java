package io.floci.aws.services.fsx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsErrorResponse;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestConstraintCheck;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** JSON 1.1 dispatch for Amazon FSx ({@code X-Amz-Target: AWSSimbaAPIService_v20180301.<Action>}), and its tags. */
@ApplicationScoped
public class FsxJsonHandler {

    /** FSx's request constraints; it reports a violation as BadRequest. */
    private static final RequestConstraintCheck SHAPES = RequestConstraintCheck.load(
            "aws/fsx-request-shapes.json", "FSx request shapes", new RequestConstraintCheck.Messages() {
                @Override
                public AwsException type(String path, String type) {
                    return violation(path, "Member must be a " + type);
                }

                @Override
                public AwsException bounds(String path, String type, Long min, Long max, long size) {
                    String what = "string".equals(type) ? "length" : Set.of("integer", "long").contains(type)
                            ? "value" : "size";
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

    /** The kinds of resource FSx tags, by the first segment of their ARN's resource. */
    private static final Set<String> TAGGABLE = Set.of(FsxStore.FILE_SYSTEM, FsxStore.BACKUP, FsxStore.VOLUME,
            FsxStore.SNAPSHOT, FsxStore.SVM, FsxStore.ASSOCIATION, FsxStore.TASK, FsxStore.CACHE);

    private final FsxStore store;
    private final FsxFileSystems fileSystems;
    private final FsxVolumes volumes;
    private final FsxRepositories repositories;

    @Inject
    public FsxJsonHandler(FsxStore store, FsxFileSystems fileSystems, FsxVolumes volumes,
                          FsxRepositories repositories) {
        this.store = store;
        this.fileSystems = fileSystems;
        this.volumes = volumes;
        this.repositories = repositories;
    }

    private static AwsException violation(String path, String constraint) {
        String member = path.contains(".") ? path.substring(path.indexOf('.') + 1) : path;
        return FsxStore.badRequest("1 validation error detected: Value at '" + member
                + "' failed to satisfy constraint: " + constraint);
    }

    public Response handle(String action, JsonNode request, String region) {
        SHAPES.check(action, request);
        ObjectNode result = switch (action) {
            case "AssociateFileSystemAliases" -> fileSystems.associateFileSystemAliases(request, region);
            case "CopyBackup" -> fileSystems.copyBackup(request, region);
            case "CreateBackup" -> fileSystems.createBackup(request, region);
            case "CreateFileCache" -> fileSystems.createFileCache(request, region);
            case "CreateFileSystem" -> fileSystems.createFileSystem(request, region);
            case "CreateFileSystemFromBackup" -> fileSystems.createFileSystemFromBackup(request, region);
            case "DeleteBackup" -> fileSystems.deleteBackup(request, region);
            case "DeleteFileCache" -> fileSystems.deleteFileCache(request, region);
            case "DeleteFileSystem" -> fileSystems.deleteFileSystem(request, region);
            case "DescribeBackups" -> fileSystems.describeBackups(request, region);
            case "DescribeFileCaches" -> fileSystems.describeFileCaches(request, region);
            case "DescribeFileSystemAliases" -> fileSystems.describeFileSystemAliases(request, region);
            case "DescribeFileSystems" -> fileSystems.describeFileSystems(request, region);
            case "DescribeSharedVpcConfiguration" -> fileSystems.describeSharedVpcConfiguration(request, region);
            case "DisassociateFileSystemAliases" -> fileSystems.disassociateFileSystemAliases(request, region);
            case "ReleaseFileSystemNfsV3Locks" -> fileSystems.releaseFileSystemNfsV3Locks(request, region);
            case "StartMisconfiguredStateRecovery" -> fileSystems.startMisconfiguredStateRecovery(request, region);
            case "UpdateFileCache" -> fileSystems.updateFileCache(request, region);
            case "UpdateFileSystem" -> fileSystems.updateFileSystem(request, region);
            case "UpdateSharedVpcConfiguration" -> fileSystems.updateSharedVpcConfiguration(request, region);
            case "CopySnapshotAndUpdateVolume" -> volumes.copySnapshotAndUpdateVolume(request, region);
            case "CreateAndAttachS3AccessPoint" -> volumes.createAndAttachS3AccessPoint(request, region);
            case "CreateSnapshot" -> volumes.createSnapshot(request, region);
            case "CreateStorageVirtualMachine" -> volumes.createStorageVirtualMachine(request, region);
            case "CreateVolume" -> volumes.createVolume(request, region);
            case "CreateVolumeFromBackup" -> volumes.createVolumeFromBackup(request, region);
            case "DeleteSnapshot" -> volumes.deleteSnapshot(request, region);
            case "DeleteStorageVirtualMachine" -> volumes.deleteStorageVirtualMachine(request, region);
            case "DeleteVolume" -> volumes.deleteVolume(request, region);
            case "DescribeS3AccessPointAttachments" -> volumes.describeS3AccessPointAttachments(request, region);
            case "DescribeSnapshots" -> volumes.describeSnapshots(request, region);
            case "DescribeStorageVirtualMachines" -> volumes.describeStorageVirtualMachines(request, region);
            case "DescribeVolumes" -> volumes.describeVolumes(request, region);
            case "DetachAndDeleteS3AccessPoint" -> volumes.detachAndDeleteS3AccessPoint(request, region);
            case "RestoreVolumeFromSnapshot" -> volumes.restoreVolumeFromSnapshot(request, region);
            case "UpdateSnapshot" -> volumes.updateSnapshot(request, region);
            case "UpdateStorageVirtualMachine" -> volumes.updateStorageVirtualMachine(request, region);
            case "UpdateVolume" -> volumes.updateVolume(request, region);
            case "CancelDataRepositoryTask" -> repositories.cancelDataRepositoryTask(request, region);
            case "CreateDataRepositoryAssociation" -> repositories.createDataRepositoryAssociation(request, region);
            case "CreateDataRepositoryTask" -> repositories.createDataRepositoryTask(request, region);
            case "DeleteDataRepositoryAssociation" -> repositories.deleteDataRepositoryAssociation(request, region);
            case "DescribeDataRepositoryAssociations" -> repositories.describeDataRepositoryAssociations(request, region);
            case "DescribeDataRepositoryTasks" -> repositories.describeDataRepositoryTasks(request, region);
            case "UpdateDataRepositoryAssociation" -> repositories.updateDataRepositoryAssociation(request, region);
            case "ListTagsForResource" -> listTagsForResource(request, region);
            case "TagResource" -> tagResource(request, region);
            case "UntagResource" -> untagResource(request, region);
            default -> null;
        };
        if (result == null) {
            return Response.status(400).entity(new AwsErrorResponse("UnknownOperationException",
                    "Unknown operation: AWSSimbaAPIService_v20180301." + action)).build();
        }
        return Response.ok(result).build();
    }

    // ---------------------------------------------------------------- tags

    private synchronized ObjectNode listTagsForResource(JsonNode input, String region) {
        ObjectNode record = taggable(region, FsxStore.text(input, "ResourceARN"));
        List<ObjectNode> tags = new ArrayList<>();
        record.path("Tags").forEach(tag -> {
            ObjectNode copy = ((ObjectNode) tag).deepCopy();
            copy.putObject("_");
            tags.add(copy);
        });
        return store.page(tags, input, "Tags");
    }

    private synchronized ObjectNode tagResource(JsonNode input, String region) {
        String arn = FsxStore.text(input, "ResourceARN");
        ObjectNode record = taggable(region, arn);
        Map<String, String> tags = new LinkedHashMap<>();
        record.path("Tags").forEach(tag -> tags.put(tag.path("Key").asText(), tag.path("Value").asText()));
        input.path("Tags").forEach(tag -> tags.put(tag.path("Key").asText(), tag.path("Value").asText("")));
        save(region, arn, record, tags);
        return store.mapper().createObjectNode();
    }

    private synchronized ObjectNode untagResource(JsonNode input, String region) {
        String arn = FsxStore.text(input, "ResourceARN");
        ObjectNode record = taggable(region, arn);
        Map<String, String> tags = new LinkedHashMap<>();
        record.path("Tags").forEach(tag -> tags.put(tag.path("Key").asText(), tag.path("Value").asText()));
        input.path("TagKeys").forEach(key -> tags.remove(key.asText()));
        save(region, arn, record, tags);
        return store.mapper().createObjectNode();
    }

    private void save(String region, String arn, ObjectNode record, Map<String, String> tags) {
        if (tags.size() > 50) {
            throw FsxStore.badRequest("A resource can have at most 50 tags.");
        }
        ArrayNode out = record.putArray("Tags");
        tags.forEach((key, value) -> out.addObject().put("Key", key).put("Value", value));
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        String[] parts = parsed.resource().split("/");
        store.put(region, parts[0], parts[parts.length - 1], record);
    }

    /** The resource a tagging request's ARN names. */
    private ObjectNode taggable(String region, String arn) {
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(arn);
        } catch (IllegalArgumentException e) {
            throw new AwsException("NotServiceResourceError", "The ARN " + arn + " is not an FSx resource.", 400,
                    Map.of("ResourceARN", arn));
        }
        if (!"fsx".equals(parsed.service())) {
            throw new AwsException("NotServiceResourceError", "The ARN " + arn + " is not an FSx resource.", 400,
                    Map.of("ResourceARN", arn));
        }
        String[] parts = parsed.resource().split("/");
        if (!TAGGABLE.contains(parts[0])) {
            throw new AwsException("ResourceDoesNotSupportTagging", "The resource " + arn + " does not support "
                    + "tagging.", 400, Map.of("ResourceARN", arn));
        }
        if (!region.equals(parsed.region()) || !store.account().equals(parsed.accountId())) {
            throw new AwsException("ResourceNotFound", "The resource " + arn + " does not exist.", 400,
                    Map.of("ResourceARN", arn));
        }
        return store.get(region, parts[0], parts[parts.length - 1]).orElseThrow(() -> new AwsException(
                "ResourceNotFound", "The resource " + arn + " does not exist.", 400, Map.of("ResourceARN", arn)));
    }
}
