package io.github.hectorvent.floci.services.fsx;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import io.github.hectorvent.floci.services.fsx.model.FsxFileSystem;
import io.github.hectorvent.floci.services.fsx.model.FsxRecord;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Amazon FSx JSON 1.1 ({@code AWSSimbaAPIService_v20180301.*}).
 *
 * <p>File systems become {@code AVAILABLE} immediately so Alchemy wait-loops
 * do not stall. Account-level describe APIs return empty collections when
 * nothing has been created; mutating a missing backup/snapshot/volume/task
 * surfaces the same typed errors AWS does (including the BadRequest message
 * predicates distilled carves into synthetic not-found tags).
 */
@ApplicationScoped
public class FsxService implements Resettable {

    static final String SERVICE = "fsx";
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final StorageBackend<String, FsxFileSystem> fileSystems;
    private final StorageBackend<String, FsxRecord> backups;
    private final StorageBackend<String, FsxRecord> snapshots;
    private final StorageBackend<String, FsxRecord> volumes;
    private final StorageBackend<String, FsxRecord> svms;
    private final StorageBackend<String, FsxRecord> tasks;
    private final StorageBackend<String, FsxRecord> associations;
    private final Ec2Service ec2Service;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public FsxService(StorageFactory factory, Ec2Service ec2Service, RegionResolver regionResolver,
                      ObjectMapper objectMapper) {
        this.fileSystems = factory.create(SERVICE, "fsx-file-systems.json",
                new TypeReference<Map<String, FsxFileSystem>>() {
                });
        this.backups = factory.create(SERVICE, "fsx-backups.json",
                new TypeReference<Map<String, FsxRecord>>() {
                });
        this.snapshots = factory.create(SERVICE, "fsx-snapshots.json",
                new TypeReference<Map<String, FsxRecord>>() {
                });
        this.volumes = factory.create(SERVICE, "fsx-volumes.json",
                new TypeReference<Map<String, FsxRecord>>() {
                });
        this.svms = factory.create(SERVICE, "fsx-svms.json",
                new TypeReference<Map<String, FsxRecord>>() {
                });
        this.tasks = factory.create(SERVICE, "fsx-tasks.json",
                new TypeReference<Map<String, FsxRecord>>() {
                });
        this.associations = factory.create(SERVICE, "fsx-associations.json",
                new TypeReference<Map<String, FsxRecord>>() {
                });
        this.ec2Service = ec2Service;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    FsxService(StorageBackend<String, FsxFileSystem> fileSystems,
               StorageBackend<String, FsxRecord> backups,
               StorageBackend<String, FsxRecord> snapshots,
               StorageBackend<String, FsxRecord> volumes,
               StorageBackend<String, FsxRecord> svms,
               StorageBackend<String, FsxRecord> tasks,
               StorageBackend<String, FsxRecord> associations,
               Ec2Service ec2Service, RegionResolver regionResolver, ObjectMapper objectMapper) {
        this.fileSystems = fileSystems;
        this.backups = backups;
        this.snapshots = snapshots;
        this.volumes = volumes;
        this.svms = svms;
        this.tasks = tasks;
        this.associations = associations;
        this.ec2Service = ec2Service;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @Override
    public void clear() {
        fileSystems.clear();
        backups.clear();
        snapshots.clear();
        volumes.clear();
        svms.clear();
        tasks.clear();
        associations.clear();
    }

    public synchronized ObjectNode createFileSystem(JsonNode request, String region) {
        String type = requireText(request, "FileSystemType");
        String token = textOrNull(request, "ClientRequestToken");
        if (token != null) {
            FsxFileSystem existing = findByToken(region, token);
            if (existing != null) {
                return wrap("FileSystem", toFileSystemNode(existing));
            }
        }
        List<String> subnetIds = stringList(request.get("SubnetIds"));
        if (subnetIds.isEmpty()) {
            throw badRequest("SubnetIds is a required parameter.");
        }

        String fileSystemId = hexId("fs-");
        FsxFileSystem fs = new FsxFileSystem();
        fs.setFileSystemId(fileSystemId);
        fs.setOwnerId(regionResolver.getAccountId());
        fs.setRegion(region);
        fs.setClientRequestToken(token);
        fs.setCreationTime(now());
        fs.setFileSystemType(type);
        fs.setLifecycle("AVAILABLE");
        if (request.hasNonNull("StorageCapacity")) {
            fs.setStorageCapacity(request.get("StorageCapacity").asInt());
        }
        fs.setStorageType(textOr(request, "StorageType", "SSD"));
        fs.setSubnetIds(subnetIds);
        fs.setSecurityGroupIds(stringList(request.get("SecurityGroupIds")));
        fs.setDnsName(fileSystemId + ".fsx." + region + ".amazonaws.com");
        fs.setKmsKeyId(textOr(request, "KmsKeyId", "alias/aws/fsx"));
        fs.setFileSystemTypeVersion(textOrNull(request, "FileSystemTypeVersion"));
        fs.setNetworkType(textOrNull(request, "NetworkType"));
        fs.setResourceArn(regionResolver.buildArn(SERVICE, region, "file-system/" + fileSystemId));
        fs.setTags(readTags(request.get("Tags")));
        fs.setLustreConfiguration(objectMap(request.get("LustreConfiguration")));
        fs.setWindowsConfiguration(objectMap(request.get("WindowsConfiguration")));
        fs.setOntapConfiguration(objectMap(request.get("OntapConfiguration")));
        fs.setOpenZFSConfiguration(objectMap(request.get("OpenZFSConfiguration")));
        if (fs.getLustreConfiguration() != null && !fs.getLustreConfiguration().containsKey("MountName")) {
            fs.getLustreConfiguration().put("MountName", fileSystemId.substring(3, Math.min(fileSystemId.length(), 11)));
        }
        resolveVpc(region, subnetIds).ifPresent(fs::setVpcId);
        fileSystems.put(storageKey(region, fileSystemId), fs);

        if ("OPENZFS".equals(type) || "ONTAP".equals(type)) {
            String volumeId = hexId("fsvol-");
            FsxRecord volume = new FsxRecord();
            volume.setId(volumeId);
            volume.setArn(regionResolver.buildArn(SERVICE, region, "volume/" + volumeId));
            volume.setRegion(region);
            volume.setName("root");
            volume.setFileSystemId(fileSystemId);
            volume.setLifecycle("AVAILABLE");
            volume.setCreationTime(now());
            volumes.put(storageKey(region, volumeId), volume);
        }
        return wrap("FileSystem", toFileSystemNode(fs));
    }

    public ObjectNode describeFileSystems(JsonNode request, String region) {
        List<String> ids = stringList(request.get("FileSystemIds"));
        List<FsxFileSystem> matches = new ArrayList<>();
        if (!ids.isEmpty()) {
            for (String id : ids) {
                matches.add(requireFileSystem(region, id));
            }
        } else {
            for (FsxFileSystem fs : fileSystems.values()) {
                if (region.equals(fs.getRegion()) && !"DELETED".equals(fs.getLifecycle())) {
                    matches.add(fs);
                }
            }
        }
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("FileSystems");
        for (FsxFileSystem fs : matches) {
            list.add(toFileSystemNode(fs));
        }
        return response;
    }

    public synchronized ObjectNode updateFileSystem(JsonNode request, String region) {
        FsxFileSystem fs = requireFileSystem(region, requireText(request, "FileSystemId"));
        if (request.hasNonNull("StorageCapacity")) {
            fs.setStorageCapacity(request.get("StorageCapacity").asInt());
        }
        fileSystems.put(storageKey(region, fs.getFileSystemId()), fs);
        return wrap("FileSystem", toFileSystemNode(fs));
    }

    public synchronized ObjectNode deleteFileSystem(JsonNode request, String region) {
        FsxFileSystem fs = requireFileSystem(region, requireText(request, "FileSystemId"));
        fileSystems.delete(storageKey(region, fs.getFileSystemId()));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("FileSystemId", fs.getFileSystemId());
        response.put("Lifecycle", "DELETED");
        return response;
    }

    public ObjectNode describeBackups(JsonNode request, String region) {
        return describeRecords(backups, region, stringList(request.get("BackupIds")), "Backups",
                "BackupNotFound", "Backup '%s' does not exist.");
    }

    public synchronized ObjectNode deleteBackup(JsonNode request, String region) {
        String id = requireText(request, "BackupId");
        FsxRecord backup = backups.get(storageKey(region, id)).orElse(null);
        if (backup == null) {
            throw typed("BackupNotFound", "Backup '" + id + "' does not exist.");
        }
        backups.delete(storageKey(region, id));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("BackupId", id);
        response.put("Lifecycle", "DELETED");
        return response;
    }

    public synchronized ObjectNode copyBackup(JsonNode request, String region) {
        String sourceId = requireText(request, "SourceBackupId");
        FsxRecord source = backups.get(storageKey(region, sourceId)).orElse(null);
        if (source == null) {
            throw typed("BackupNotFound", "Backup '" + sourceId + "' does not exist.");
        }
        String id = hexId("backup-");
        FsxRecord copy = new FsxRecord();
        copy.setId(id);
        copy.setArn(regionResolver.buildArn(SERVICE, region, "backup/" + id));
        copy.setRegion(region);
        copy.setSourceId(sourceId);
        copy.setFileSystemId(source.getFileSystemId());
        copy.setLifecycle("AVAILABLE");
        copy.setType("USER_INITIATED");
        copy.setCreationTime(now());
        copy.setTags(readTags(request.get("Tags")));
        backups.put(storageKey(region, id), copy);
        return wrap("Backup", toBackupNode(copy));
    }

    public ObjectNode describeSnapshots(JsonNode request, String region) {
        return describeRecords(snapshots, region, stringList(request.get("SnapshotIds")), "Snapshots",
                "SnapshotNotFound", "Snapshot '%s' is not found.");
    }

    public synchronized ObjectNode createSnapshot(JsonNode request, String region) {
        String volumeId = requireText(request, "VolumeId");
        FsxRecord volume = volumes.get(storageKey(region, volumeId)).orElse(null);
        if (volume == null) {
            throw badRequest("The volume was not found.");
        }
        String id = hexId("fsvolsnap-");
        FsxRecord snapshot = new FsxRecord();
        snapshot.setId(id);
        snapshot.setArn(regionResolver.buildArn(SERVICE, region, "snapshot/" + id));
        snapshot.setRegion(region);
        snapshot.setName(textOrNull(request, "Name"));
        snapshot.setVolumeId(volumeId);
        snapshot.setFileSystemId(volume.getFileSystemId());
        snapshot.setLifecycle("AVAILABLE");
        snapshot.setCreationTime(now());
        snapshot.setTags(readTags(request.get("Tags")));
        snapshots.put(storageKey(region, id), snapshot);
        return wrap("Snapshot", toSnapshotNode(snapshot));
    }

    public synchronized ObjectNode updateSnapshot(JsonNode request, String region) {
        String id = requireText(request, "SnapshotId");
        FsxRecord snapshot = snapshots.get(storageKey(region, id)).orElse(null);
        if (snapshot == null) {
            throw badRequest("the snapshot is not found");
        }
        if (request.hasNonNull("Name")) {
            snapshot.setName(request.get("Name").asText());
        }
        snapshots.put(storageKey(region, id), snapshot);
        return wrap("Snapshot", toSnapshotNode(snapshot));
    }

    public synchronized ObjectNode deleteSnapshot(JsonNode request, String region) {
        String id = requireText(request, "SnapshotId");
        FsxRecord snapshot = snapshots.get(storageKey(region, id)).orElse(null);
        if (snapshot == null) {
            throw typed("SnapshotNotFound", "Snapshot '" + id + "' is not found.");
        }
        snapshots.delete(storageKey(region, id));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("SnapshotId", id);
        response.put("Lifecycle", "DELETED");
        return response;
    }

    public ObjectNode describeVolumes(JsonNode request, String region) {
        return describeRecords(volumes, region, stringList(request.get("VolumeIds")), "Volumes",
                "VolumeNotFound", "Volume '%s' is not found.");
    }

    public synchronized ObjectNode restoreVolumeFromSnapshot(JsonNode request, String region) {
        requireText(request, "VolumeId");
        String snapshotId = requireText(request, "SnapshotId");
        if (snapshots.get(storageKey(region, snapshotId)).isEmpty()) {
            throw badRequest("The snapshot cannot be found.");
        }
        if (volumes.get(storageKey(region, request.get("VolumeId").asText())).isEmpty()) {
            throw typed("VolumeNotFound", "Volume '" + request.get("VolumeId").asText() + "' is not found.");
        }
        return objectMapper.createObjectNode();
    }

    public synchronized ObjectNode copySnapshotAndUpdateVolume(JsonNode request, String region) {
        requireText(request, "VolumeId");
        String sourceArn = requireText(request, "SourceSnapshotARN");
        String snapshotId = lastSegment(sourceArn);
        boolean found = false;
        for (FsxRecord snapshot : snapshots.values()) {
            if (sourceArn.equals(snapshot.getArn()) || snapshotId.equals(snapshot.getId())) {
                found = true;
                break;
            }
        }
        if (!found) {
            throw badRequest("SourceSnapshotARN provided is not a valid ARN");
        }
        return objectMapper.createObjectNode();
    }

    public ObjectNode describeStorageVirtualMachines(JsonNode request, String region) {
        return describeRecords(svms, region, stringList(request.get("StorageVirtualMachineIds")),
                "StorageVirtualMachines", "StorageVirtualMachineNotFound",
                "Storage virtual machine '%s' is not found.");
    }

    public ObjectNode describeDataRepositoryTasks(JsonNode request, String region) {
        return describeRecords(tasks, region, stringList(request.get("TaskIds")), "DataRepositoryTasks",
                "DataRepositoryTaskNotFound", "Data repository task '%s' is not found.");
    }

    public ObjectNode describeDataRepositoryAssociations(JsonNode request, String region) {
        return describeRecords(associations, region, stringList(request.get("AssociationIds")),
                "Associations", "DataRepositoryAssociationNotFound",
                "Data repository association '%s' is not found.");
    }

    public synchronized ObjectNode cancelDataRepositoryTask(JsonNode request, String region) {
        String id = requireText(request, "TaskId");
        FsxRecord task = tasks.get(storageKey(region, id)).orElse(null);
        if (task == null) {
            throw typed("DataRepositoryTaskNotFound", "Data repository task '" + id + "' is not found.");
        }
        task.setLifecycle("CANCELING");
        tasks.put(storageKey(region, id), task);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("Lifecycle", "CANCELING");
        response.put("TaskId", id);
        return response;
    }

    public synchronized ObjectNode tagResource(JsonNode request, String region) {
        String arn = requireText(request, "ResourceARN");
        FsxFileSystem fs = requireFileSystemByArn(region, arn);
        fs.getTags().putAll(readTags(request.get("Tags")));
        fileSystems.put(storageKey(region, fs.getFileSystemId()), fs);
        return objectMapper.createObjectNode();
    }

    public synchronized ObjectNode untagResource(JsonNode request, String region) {
        String arn = requireText(request, "ResourceARN");
        FsxFileSystem fs = requireFileSystemByArn(region, arn);
        for (String key : stringList(request.get("TagKeys"))) {
            fs.getTags().remove(key);
        }
        fileSystems.put(storageKey(region, fs.getFileSystemId()), fs);
        return objectMapper.createObjectNode();
    }

    public ObjectNode listTagsForResource(JsonNode request, String region) {
        FsxFileSystem fs = requireFileSystemByArn(region, requireText(request, "ResourceARN"));
        ObjectNode response = objectMapper.createObjectNode();
        writeTags(response.putArray("Tags"), fs.getTags());
        return response;
    }

    private ObjectNode describeRecords(StorageBackend<String, FsxRecord> store, String region,
                                       List<String> ids, String listKey, String missingCode, String missingFmt) {
        List<FsxRecord> matches = new ArrayList<>();
        if (!ids.isEmpty()) {
            for (String id : ids) {
                FsxRecord record = store.get(storageKey(region, id)).orElse(null);
                if (record == null) {
                    throw typed(missingCode, missingFmt.formatted(id));
                }
                matches.add(record);
            }
        } else {
            for (FsxRecord record : store.values()) {
                if (region.equals(record.getRegion())) {
                    matches.add(record);
                }
            }
        }
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray(listKey);
        for (FsxRecord record : matches) {
            list.add(toRecordNode(record, listKey));
        }
        return response;
    }

    private ObjectNode toRecordNode(FsxRecord record, String listKey) {
        return switch (listKey) {
            case "Backups" -> toBackupNode(record);
            case "Snapshots" -> toSnapshotNode(record);
            case "Volumes" -> toVolumeNode(record);
            case "StorageVirtualMachines" -> toSvmNode(record);
            case "DataRepositoryTasks" -> toTaskNode(record);
            default -> toAssociationNode(record);
        };
    }

    private ObjectNode toFileSystemNode(FsxFileSystem fs) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("OwnerId", fs.getOwnerId());
        node.put("CreationTime", fs.getCreationTime());
        node.put("FileSystemId", fs.getFileSystemId());
        node.put("FileSystemType", fs.getFileSystemType());
        node.put("Lifecycle", fs.getLifecycle());
        if (fs.getStorageCapacity() != null) {
            node.put("StorageCapacity", fs.getStorageCapacity());
        }
        if (fs.getStorageType() != null) {
            node.put("StorageType", fs.getStorageType());
        }
        if (fs.getVpcId() != null) {
            node.put("VpcId", fs.getVpcId());
        }
        ArrayNode subnets = node.putArray("SubnetIds");
        fs.getSubnetIds().forEach(subnets::add);
        if (fs.getDnsName() != null) {
            node.put("DNSName", fs.getDnsName());
        }
        if (fs.getKmsKeyId() != null) {
            node.put("KmsKeyId", fs.getKmsKeyId());
        }
        node.put("ResourceARN", fs.getResourceArn());
        writeTags(node.putArray("Tags"), fs.getTags());
        putObject(node, "LustreConfiguration", fs.getLustreConfiguration());
        putObject(node, "WindowsConfiguration", fs.getWindowsConfiguration());
        putObject(node, "OntapConfiguration", fs.getOntapConfiguration());
        putObject(node, "OpenZFSConfiguration", fs.getOpenZFSConfiguration());
        if (fs.getFileSystemTypeVersion() != null) {
            node.put("FileSystemTypeVersion", fs.getFileSystemTypeVersion());
        }
        if (fs.getNetworkType() != null) {
            node.put("NetworkType", fs.getNetworkType());
        }
        return node;
    }

    private ObjectNode toBackupNode(FsxRecord backup) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("BackupId", backup.getId());
        node.put("Lifecycle", backup.getLifecycle());
        node.put("Type", backup.getType() != null ? backup.getType() : "USER_INITIATED");
        node.put("CreationTime", backup.getCreationTime());
        if (backup.getFileSystemId() != null) {
            node.put("FileSystemId", backup.getFileSystemId());
        }
        node.put("ResourceARN", backup.getArn());
        writeTags(node.putArray("Tags"), backup.getTags());
        return node;
    }

    private ObjectNode toSnapshotNode(FsxRecord snapshot) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("SnapshotId", snapshot.getId());
        if (snapshot.getName() != null) {
            node.put("Name", snapshot.getName());
        }
        node.put("VolumeId", snapshot.getVolumeId());
        node.put("Lifecycle", snapshot.getLifecycle());
        node.put("CreationTime", snapshot.getCreationTime());
        node.put("ResourceARN", snapshot.getArn());
        writeTags(node.putArray("Tags"), snapshot.getTags());
        return node;
    }

    private ObjectNode toVolumeNode(FsxRecord volume) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("VolumeId", volume.getId());
        node.put("VolumeType", "OPENZFS");
        node.put("Lifecycle", volume.getLifecycle());
        node.put("FileSystemId", volume.getFileSystemId());
        if (volume.getName() != null) {
            node.put("Name", volume.getName());
        }
        node.put("ResourceARN", volume.getArn());
        node.put("CreationTime", volume.getCreationTime());
        writeTags(node.putArray("Tags"), volume.getTags());
        return node;
    }

    private ObjectNode toSvmNode(FsxRecord svm) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("StorageVirtualMachineId", svm.getId());
        node.put("Lifecycle", svm.getLifecycle());
        node.put("FileSystemId", svm.getFileSystemId());
        if (svm.getName() != null) {
            node.put("Name", svm.getName());
        }
        node.put("ResourceARN", svm.getArn());
        writeTags(node.putArray("Tags"), svm.getTags());
        return node;
    }

    private ObjectNode toTaskNode(FsxRecord task) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("TaskId", task.getId());
        node.put("Lifecycle", task.getLifecycle());
        node.put("Type", task.getType() != null ? task.getType() : "EXPORT_TO_REPOSITORY");
        node.put("CreationTime", task.getCreationTime());
        node.put("ResourceARN", task.getArn());
        return node;
    }

    private ObjectNode toAssociationNode(FsxRecord association) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("AssociationId", association.getId());
        node.put("Lifecycle", association.getLifecycle());
        node.put("FileSystemId", association.getFileSystemId());
        node.put("ResourceARN", association.getArn());
        node.put("CreationTime", association.getCreationTime());
        return node;
    }

    private FsxFileSystem requireFileSystem(String region, String fileSystemId) {
        FsxFileSystem fs = fileSystems.get(storageKey(region, fileSystemId)).orElse(null);
        if (fs == null) {
            throw typed("FileSystemNotFound", "There is no file system with ID '" + fileSystemId + "'.");
        }
        return fs;
    }

    private FsxFileSystem requireFileSystemByArn(String region, String arn) {
        for (FsxFileSystem fs : fileSystems.values()) {
            if (arn.equals(fs.getResourceArn()) && region.equals(fs.getRegion())) {
                return fs;
            }
        }
        throw typed("ResourceNotFound", "Resource '" + arn + "' is not found.");
    }

    private FsxFileSystem findByToken(String region, String token) {
        for (FsxFileSystem fs : fileSystems.values()) {
            if (region.equals(fs.getRegion()) && token.equals(fs.getClientRequestToken())) {
                return fs;
            }
        }
        return null;
    }

    private Optional<String> resolveVpc(String region, List<String> subnetIds) {
        for (String subnetId : subnetIds) {
            Optional<Subnet> subnet = ec2Service.findSubnetById(region, subnetId);
            if (subnet.isPresent() && subnet.get().getVpcId() != null) {
                return Optional.of(subnet.get().getVpcId());
            }
        }
        return Optional.empty();
    }

    private ObjectNode wrap(String key, ObjectNode value) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set(key, value);
        return response;
    }

    private void putObject(ObjectNode parent, String field, Map<String, Object> value) {
        if (value != null && !value.isEmpty()) {
            parent.set(field, objectMapper.valueToTree(value));
        }
    }

    private static void writeTags(ArrayNode array, Map<String, String> tags) {
        for (Map.Entry<String, String> entry : tags.entrySet()) {
            ObjectNode tag = array.addObject();
            tag.put("Key", entry.getKey());
            tag.put("Value", entry.getValue());
        }
    }

    private Map<String, String> readTags(JsonNode tags) {
        Map<String, String> result = new LinkedHashMap<>();
        if (tags == null || !tags.isArray()) {
            return result;
        }
        for (JsonNode tag : tags) {
            if (tag.hasNonNull("Key") && tag.hasNonNull("Value")) {
                result.put(tag.get("Key").asText(), tag.get("Value").asText());
            }
        }
        return result;
    }

    private Map<String, Object> objectMap(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject()) {
            return null;
        }
        return objectMapper.convertValue(node, MAP);
    }

    private static List<String> stringList(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return values;
        }
        for (JsonNode item : node) {
            if (!item.isNull()) {
                String text = item.asText();
                if (text != null && !text.isBlank()) {
                    values.add(text);
                }
            }
        }
        return values;
    }

    private static String requireText(JsonNode request, String field) {
        String value = textOrNull(request, field);
        if (value == null) {
            throw badRequest(field + " is a required parameter.");
        }
        return value;
    }

    private static String textOrNull(JsonNode request, String field) {
        if (request == null || !request.hasNonNull(field)) {
            return null;
        }
        String value = request.get(field).asText();
        return value == null || value.isBlank() ? null : value;
    }

    private static String textOr(JsonNode request, String field, String fallback) {
        String value = textOrNull(request, field);
        return value != null ? value : fallback;
    }

    private static String storageKey(String region, String id) {
        return region + ":" + id;
    }

    private static String lastSegment(String arn) {
        int idx = arn.lastIndexOf('/');
        return idx < 0 ? arn : arn.substring(idx + 1);
    }

    private static String hexId(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 17);
    }

    private static long now() {
        return Instant.now().getEpochSecond();
    }

    private static AwsException badRequest(String message) {
        return new AwsException("BadRequest", message, 400);
    }

    private static AwsException typed(String code, String message) {
        return new AwsException(code, message, 400);
    }
}
