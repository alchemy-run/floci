package io.floci.aws.services.fsx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsRegions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static io.floci.aws.services.fsx.FsxStore.ACCESS_POINT;
import static io.floci.aws.services.fsx.FsxStore.FILE_SYSTEM;
import static io.floci.aws.services.fsx.FsxStore.SNAPSHOT;
import static io.floci.aws.services.fsx.FsxStore.SVM;
import static io.floci.aws.services.fsx.FsxStore.VOLUME;
import static io.floci.aws.services.fsx.FsxStore.badRequest;
import static io.floci.aws.services.fsx.FsxStore.meta;
import static io.floci.aws.services.fsx.FsxStore.notFound;
import static io.floci.aws.services.fsx.FsxStore.now;
import static io.floci.aws.services.fsx.FsxStore.optional;
import static io.floci.aws.services.fsx.FsxStore.strings;
import static io.floci.aws.services.fsx.FsxStore.text;
import static io.floci.aws.services.fsx.FsxStore.view;

/**
 * ONTAP storage virtual machines and volumes, OpenZFS volumes and their snapshots, and S3 access points attached to
 * volumes. An OpenZFS file system has a root volume, under which child volumes nest by path; an ONTAP storage
 * virtual machine has a root volume, and its volumes are mounted at junction paths. A volume a file system or
 * storage virtual machine is built on cannot be deleted while it has children.
 */
@ApplicationScoped
public class FsxVolumes {

    private final FsxStore store;
    private final FsxFileSystems fileSystems;

    @Inject
    public FsxVolumes(FsxStore store, FsxFileSystems fileSystems) {
        this.store = store;
        this.fileSystems = fileSystems;
    }

    // ---------------------------------------------------------------- storage virtual machines

    public synchronized ObjectNode createStorageVirtualMachine(JsonNode input, String region) {
        String fileSystemId = text(input, "FileSystemId");
        ObjectNode fileSystem = fileSystems.fileSystem(region, fileSystemId);
        if (!"ONTAP".equals(fileSystem.path("FileSystemType").asText())) {
            throw badRequest("Storage virtual machines are for FSx for NetApp ONTAP file systems.");
        }
        String name = text(input, "Name");
        for (ObjectNode existing : svms(region, fileSystemId)) {
            if (name.equals(existing.path("Name").asText())) {
                throw badRequest("A storage virtual machine named " + name + " already exists on " + fileSystemId
                        + ".");
            }
        }
        if (svms(region, fileSystemId).size() >= 24) {
            throw notFound("ServiceLimitExceeded", "A file system has at most 24 storage virtual machines.");
        }
        String id = store.id("svm");
        ObjectNode svm = store.record("StorageVirtualMachineId", id);
        svm.put("CreationTime", now()).put("FileSystemId", fileSystemId).put("Lifecycle", "CREATING")
                .put("Name", name).put("ResourceARN", store.arn(region, "storage-virtual-machine/" + fileSystemId
                        + "/" + id)).put("Subtype", "DEFAULT").put("UUID", UUID.randomUUID().toString())
                .put("RootVolumeSecurityStyle", input.path("RootVolumeSecurityStyle").asText("UNIX"));
        JsonNode directory = input.path("ActiveDirectoryConfiguration");
        if (!directory.isMissingNode()) {
            ObjectNode ad = svm.putObject("ActiveDirectoryConfiguration");
            ad.put("NetBiosName", text(directory, "NetBiosName"));
            JsonNode managed = directory.path("SelfManagedActiveDirectoryConfiguration");
            if (!managed.isMissingNode()) {
                ObjectNode attributes = ad.putObject("SelfManagedActiveDirectoryConfiguration");
                attributes.put("DomainName", text(managed, "DomainName"));
                for (String field : List.of("OrganizationalUnitDistinguishedName", "FileSystemAdministratorsGroup",
                        "UserName")) {
                    if (managed.hasNonNull(field)) {
                        attributes.put(field, managed.get(field).asText());
                    }
                }
                attributes.set("DnsIps", managed.path("DnsIps").deepCopy());
            }
        }
        String ip = fileSystem.path("OntapConfiguration").path("Endpoints").path("Management").path("IpAddresses")
                .path(0).asText("198.19.255.10");
        String suffix = "." + id + "." + fileSystemId + ".fsx." + region + "." + AwsRegions.dnsSuffixFor(region);
        ObjectNode endpoints = svm.putObject("Endpoints");
        for (String endpoint : directory.isMissingNode() ? List.of("Iscsi", "Management", "Nfs")
                : List.of("Iscsi", "Management", "Nfs", "Smb")) {
            ObjectNode out = endpoints.putObject(endpoint);
            out.put("DNSName", (endpoint.equals("Smb") ? directory.path("NetBiosName").asText().toLowerCase()
                    : endpoint.toLowerCase()) + suffix);
            out.putArray("IpAddresses").add(ip);
        }
        svm.set("Tags", store.tags(input.get("Tags")));
        if (input.hasNonNull("SvmAdminPassword")) {
            meta(svm).put("adminPassword", input.get("SvmAdminPassword").asText());
        }
        store.transition(region, SVM, svm, "Lifecycle", "CREATING", "CREATED");
        ObjectNode root = ontapVolume(region, fileSystemId, svm, name + "_root", "/", 1024,
                svm.path("RootVolumeSecurityStyle").asText(), true);
        store.transition(region, VOLUME, root, "Lifecycle", "CREATING", "CREATED");
        return wrap("StorageVirtualMachine", svm);
    }

    public synchronized ObjectNode describeStorageVirtualMachines(JsonNode input, String region) {
        List<String> ids = strings(input.path("StorageVirtualMachineIds"));
        List<ObjectNode> items = new ArrayList<>();
        if (!ids.isEmpty()) {
            for (String id : ids) {
                items.add(svm(region, id));
            }
        } else {
            for (ObjectNode svm : store.list(region, SVM, record -> true)) {
                if (FsxStore.filtered(input.path("Filters"), Map.of("file-system-id",
                        svm.path("FileSystemId").asText()))) {
                    items.add(svm);
                }
            }
        }
        return store.page(items, input, "StorageVirtualMachines");
    }

    public synchronized ObjectNode updateStorageVirtualMachine(JsonNode input, String region) {
        ObjectNode svm = svm(region, text(input, "StorageVirtualMachineId"));
        if (input.hasNonNull("SvmAdminPassword")) {
            meta(svm).put("adminPassword", input.get("SvmAdminPassword").asText());
        }
        JsonNode directory = input.path("ActiveDirectoryConfiguration");
        if (!directory.isMissingNode()) {
            ObjectNode ad = svm.withObject("/ActiveDirectoryConfiguration");
            if (directory.hasNonNull("NetBiosName")) {
                ad.put("NetBiosName", directory.get("NetBiosName").asText());
            }
        }
        store.put(region, SVM, svm.path("StorageVirtualMachineId").asText(), svm);
        return wrap("StorageVirtualMachine", svm);
    }

    /** Deletes a storage virtual machine with no volumes but its root, which goes with it. */
    public synchronized ObjectNode deleteStorageVirtualMachine(JsonNode input, String region) {
        String id = text(input, "StorageVirtualMachineId");
        ObjectNode svm = svm(region, id);
        List<ObjectNode> svmVolumes = store.list(region, VOLUME, volume -> id.equals(volume.path("OntapConfiguration")
                .path("StorageVirtualMachineId").asText()));
        for (ObjectNode volume : svmVolumes) {
            if (!volume.path("OntapConfiguration").path("StorageVirtualMachineRoot").asBoolean(false)) {
                throw badRequest("Storage virtual machine " + id + " has volumes; delete them first.");
            }
        }
        svmVolumes.forEach(volume -> store.delete(region, VOLUME, volume.path("VolumeId").asText()));
        store.transition(region, SVM, svm, "Lifecycle", "DELETING", null);
        ObjectNode response = store.mapper().createObjectNode();
        response.put("StorageVirtualMachineId", id).put("Lifecycle", "DELETING");
        return response;
    }

    private List<ObjectNode> svms(String region, String fileSystemId) {
        return store.list(region, SVM, svm -> fileSystemId.equals(svm.path("FileSystemId").asText()));
    }

    ObjectNode svm(String region, String id) {
        return store.get(region, SVM, id).orElseThrow(() -> notFound("StorageVirtualMachineNotFound",
                "Storage virtual machine " + id + " does not exist."));
    }

    // ---------------------------------------------------------------- volumes

    /** An OpenZFS file system's root volume, {@code /fsx}, created with it. */
    void rootVolume(String region, ObjectNode fileSystem, JsonNode config) {
        String fileSystemId = fileSystem.path("FileSystemId").asText();
        String id = store.id("fsvol");
        ObjectNode volume = store.record("VolumeId", id);
        volume.put("CreationTime", now()).put("FileSystemId", fileSystemId).put("Lifecycle", "CREATING")
                .put("Name", "fsx").put("ResourceARN", store.arn(region, "volume/" + fileSystemId + "/" + id))
                .put("VolumeType", "OPENZFS");
        ObjectNode out = volume.putObject("OpenZFSConfiguration");
        out.put("VolumePath", "/fsx").put("StorageCapacityReservationGiB", 0)
                .put("StorageCapacityQuotaGiB", fileSystem.path("StorageCapacity").asInt())
                .put("RecordSizeKiB", config.path("RecordSizeKiB").asInt(128))
                .put("DataCompressionType", config.path("DataCompressionType").asText("NONE"))
                .put("CopyTagsToSnapshots", config.path("CopyTagsToSnapshots").asBoolean(false))
                .put("ReadOnly", config.path("ReadOnly").asBoolean(false));
        out.set("NfsExports", config.has("NfsExports") ? config.get("NfsExports").deepCopy() : defaultExports());
        out.set("UserAndGroupQuotas", config.path("UserAndGroupQuotas").isArray()
                ? config.get("UserAndGroupQuotas").deepCopy() : store.mapper().createArrayNode());
        volume.set("Tags", fileSystem.path("OpenZFSConfiguration").path("CopyTagsToVolumes").asBoolean(false)
                ? fileSystem.path("Tags").deepCopy() : store.mapper().createArrayNode());
        store.transition(region, VOLUME, volume, "Lifecycle", "CREATING", "AVAILABLE");
        fileSystem.withObject("/OpenZFSConfiguration").put("RootVolumeId", id);
        store.put(region, FILE_SYSTEM, fileSystemId, fileSystem);
    }

    private ArrayNode defaultExports() {
        ArrayNode exports = store.mapper().createArrayNode();
        exports.addObject().putArray("ClientConfigurations").addObject().put("Clients", "*")
                .putArray("Options").add("rw").add("crossmnt");
        return exports;
    }

    public synchronized ObjectNode createVolume(JsonNode input, String region) {
        String type = text(input, "VolumeType");
        String name = text(input, "Name");
        ObjectNode volume;
        if ("ONTAP".equals(type)) {
            JsonNode config = input.path("OntapConfiguration");
            ObjectNode svm = svm(region, text(config, "StorageVirtualMachineId"));
            String fileSystemId = svm.path("FileSystemId").asText();
            long megabytes = config.hasNonNull("SizeInBytes") ? config.get("SizeInBytes").asLong() / 1048576
                    : config.path("SizeInMegabytes").asLong(0);
            if (megabytes < 20) {
                throw badRequest("An ONTAP volume needs SizeInMegabytes (or SizeInBytes) of at least 20 MiB.");
            }
            String junction = optional(config, "JunctionPath");
            for (ObjectNode existing : store.list(region, VOLUME, v -> svm.path("StorageVirtualMachineId").asText()
                    .equals(v.path("OntapConfiguration").path("StorageVirtualMachineId").asText()))) {
                if (name.equals(existing.path("Name").asText())) {
                    throw badRequest("A volume named " + name + " already exists on the storage virtual machine.");
                }
                if (junction != null && junction.equals(existing.path("OntapConfiguration").path("JunctionPath")
                        .asText(null))) {
                    throw badRequest("Junction path " + junction + " is already in use.");
                }
            }
            volume = ontapVolume(region, fileSystemId, svm, name, junction, megabytes,
                    config.path("SecurityStyle").asText(svm.path("RootVolumeSecurityStyle").asText("UNIX")), false);
            ObjectNode out = volume.withObject("/OntapConfiguration");
            out.put("StorageEfficiencyEnabled", config.path("StorageEfficiencyEnabled").asBoolean(false))
                    .put("OntapVolumeType", config.path("OntapVolumeType").asText("RW"))
                    .put("SnapshotPolicy", config.path("SnapshotPolicy").asText("default"))
                    .put("CopyTagsToBackups", config.path("CopyTagsToBackups").asBoolean(false))
                    .put("VolumeStyle", config.path("VolumeStyle").asText("FLEXVOL"));
            if (config.has("TieringPolicy")) {
                out.set("TieringPolicy", config.get("TieringPolicy").deepCopy());
            }
            if (config.has("SnaplockConfiguration")) {
                out.set("SnaplockConfiguration", config.get("SnaplockConfiguration").deepCopy());
            }
            volume.set("Tags", store.tags(input.get("Tags")));
            store.transition(region, VOLUME, volume, "Lifecycle", "CREATING", "CREATED");
        } else if ("OPENZFS".equals(type)) {
            JsonNode config = input.path("OpenZFSConfiguration");
            String parentId = text(config, "ParentVolumeId");
            ObjectNode parent = volume(region, parentId);
            String fileSystemId = parent.path("FileSystemId").asText();
            String path = parent.path("OpenZFSConfiguration").path("VolumePath").asText() + "/" + name;
            for (ObjectNode existing : store.list(region, VOLUME, v -> fileSystemId.equals(v.path("FileSystemId")
                    .asText()))) {
                if (path.equals(existing.path("OpenZFSConfiguration").path("VolumePath").asText())) {
                    throw badRequest("A volume already exists at " + path + ".");
                }
            }
            String id = store.id("fsvol");
            volume = store.record("VolumeId", id);
            volume.put("CreationTime", now()).put("FileSystemId", fileSystemId).put("Lifecycle", "CREATING")
                    .put("Name", name).put("ResourceARN", store.arn(region, "volume/" + fileSystemId + "/" + id))
                    .put("VolumeType", "OPENZFS");
            ObjectNode out = volume.putObject("OpenZFSConfiguration");
            out.put("ParentVolumeId", parentId).put("VolumePath", path)
                    .put("StorageCapacityReservationGiB", config.path("StorageCapacityReservationGiB").asInt(-1))
                    .put("StorageCapacityQuotaGiB", config.path("StorageCapacityQuotaGiB").asInt(-1))
                    .put("RecordSizeKiB", config.path("RecordSizeKiB").asInt(128))
                    .put("DataCompressionType", config.path("DataCompressionType").asText("NONE"))
                    .put("CopyTagsToSnapshots", config.path("CopyTagsToSnapshots").asBoolean(false))
                    .put("ReadOnly", config.path("ReadOnly").asBoolean(false));
            out.set("NfsExports", config.has("NfsExports") ? config.get("NfsExports").deepCopy() : defaultExports());
            out.set("UserAndGroupQuotas", config.path("UserAndGroupQuotas").isArray()
                    ? config.get("UserAndGroupQuotas").deepCopy() : store.mapper().createArrayNode());
            JsonNode origin = config.path("OriginSnapshot");
            if (!origin.isMissingNode()) {
                String snapshotArn = text(origin, "SnapshotARN");
                snapshotByArn(region, snapshotArn).orElseThrow(() -> badRequest("The OriginSnapshot "
                        + snapshotArn + " cannot be found."));
                out.putObject("OriginSnapshot").put("SnapshotARN", snapshotArn)
                        .put("CopyStrategy", text(origin, "CopyStrategy"));
            }
            volume.set("Tags", store.tags(input.get("Tags")));
            store.transition(region, VOLUME, volume, "Lifecycle", "CREATING", "AVAILABLE");
        } else {
            throw badRequest("VolumeType " + type + " is not valid.");
        }
        return wrap("Volume", volume);
    }

    ObjectNode ontapVolume(String region, String fileSystemId, ObjectNode svm, String name, String junction,
                           long megabytes, String securityStyle, boolean root) {
        String id = store.id("fsvol");
        ObjectNode volume = store.record("VolumeId", id);
        volume.put("CreationTime", now()).put("FileSystemId", fileSystemId).put("Lifecycle", "CREATING")
                .put("Name", name).put("ResourceARN", store.arn(region, "volume/" + fileSystemId + "/" + id))
                .put("VolumeType", "ONTAP");
        ObjectNode out = volume.putObject("OntapConfiguration");
        out.put("FlexCacheEndpointType", "NONE").put("SecurityStyle", securityStyle)
                .put("SizeInMegabytes", megabytes).put("SizeInBytes", megabytes * 1048576)
                .put("StorageVirtualMachineId", svm.path("StorageVirtualMachineId").asText())
                .put("StorageVirtualMachineRoot", root).put("UUID", UUID.randomUUID().toString())
                .put("OntapVolumeType", "RW").put("StorageEfficiencyEnabled", false).put("VolumeStyle", "FLEXVOL");
        if (junction != null) {
            out.put("JunctionPath", junction);
        }
        out.putObject("TieringPolicy").put("Name", "SNAPSHOT_ONLY").put("CoolingPeriod", 31);
        volume.set("Tags", store.mapper().createArrayNode());
        return volume;
    }

    /** Restores an ONTAP volume from a backup of it onto a storage virtual machine. */
    public synchronized ObjectNode createVolumeFromBackup(JsonNode input, String region) {
        String backupId = text(input, "BackupId");
        ObjectNode backup = store.get(region, FsxStore.BACKUP, backupId).orElseThrow(() -> notFound("BackupNotFound",
                "Backup " + backupId + " does not exist."));
        if (!backup.has("Volume")) {
            throw badRequest("Backup " + backupId + " is not a backup of a volume.");
        }
        ObjectNode request = store.mapper().createObjectNode();
        request.put("VolumeType", "ONTAP").put("Name", text(input, "Name"));
        ObjectNode config = backup.path("Volume").path("OntapConfiguration").deepCopy();
        config.remove(List.of("UUID", "StorageVirtualMachineRoot", "FlexCacheEndpointType", "SizeInBytes"));
        input.path("OntapConfiguration").fields().forEachRemaining(entry -> config.set(entry.getKey(),
                entry.getValue().deepCopy()));
        request.set("OntapConfiguration", config);
        if (input.has("Tags")) {
            request.set("Tags", input.get("Tags").deepCopy());
        }
        return createVolume(request, region);
    }

    public synchronized ObjectNode updateVolume(JsonNode input, String region) {
        String id = text(input, "VolumeId");
        ObjectNode volume = volume(region, id);
        if (input.hasNonNull("Name")) {
            volume.put("Name", input.get("Name").asText());
        }
        String field = "ONTAP".equals(volume.path("VolumeType").asText()) ? "OntapConfiguration"
                : "OpenZFSConfiguration";
        JsonNode update = input.path(field);
        ObjectNode config = volume.withObject("/" + field);
        update.fields().forEachRemaining(entry -> config.set(entry.getKey(), entry.getValue().deepCopy()));
        if (update.hasNonNull("SizeInMegabytes")) {
            config.put("SizeInBytes", update.get("SizeInMegabytes").asLong() * 1048576);
        }
        volume.withArray("/AdministrativeActions").addObject().put("AdministrativeActionType", "VOLUME_UPDATE")
                .put("Status", "IN_PROGRESS").put("RequestTime", now()).put("ProgressPercent", 0);
        store.transition(region, VOLUME, volume, "Lifecycle", "PENDING", "ONTAP".equals(volume.path("VolumeType")
                .asText()) ? "CREATED" : "AVAILABLE");
        return wrap("Volume", volume);
    }

    /**
     * Deletes a volume. A file system's or storage virtual machine's root volume cannot be deleted; an OpenZFS
     * volume with children or snapshots needs {@code DELETE_CHILD_VOLUMES_AND_SNAPSHOTS}; an ONTAP volume gets a
     * final backup unless it is skipped.
     */
    public synchronized ObjectNode deleteVolume(JsonNode input, String region) {
        String id = text(input, "VolumeId");
        ObjectNode volume = volume(region, id);
        ObjectNode response = store.mapper().createObjectNode();
        response.put("VolumeId", id).put("Lifecycle", "DELETING");
        if ("ONTAP".equals(volume.path("VolumeType").asText())) {
            if (volume.path("OntapConfiguration").path("StorageVirtualMachineRoot").asBoolean(false)) {
                throw badRequest("The root volume of a storage virtual machine cannot be deleted.");
            }
            JsonNode options = input.path("OntapConfiguration");
            ObjectNode out = response.putObject("OntapResponse");
            if (!options.path("SkipFinalBackup").asBoolean(false)) {
                ObjectNode fileSystem = fileSystems.fileSystem(region, volume.path("FileSystemId").asText());
                out.put("FinalBackupId", fileSystems.backupOf(region, fileSystem, volume, "USER_INITIATED",
                        options.get("FinalBackupTags")));
            }
            out.set("FinalBackupTags", options.path("FinalBackupTags").isArray()
                    ? options.get("FinalBackupTags").deepCopy() : store.mapper().createArrayNode());
        } else {
            if (!volume.path("OpenZFSConfiguration").hasNonNull("ParentVolumeId")) {
                throw badRequest("The root volume of a file system cannot be deleted.");
            }
            boolean cascade = strings(input.path("OpenZFSConfiguration").path("Options"))
                    .contains("DELETE_CHILD_VOLUMES_AND_SNAPSHOTS");
            String path = volume.path("OpenZFSConfiguration").path("VolumePath").asText();
            List<ObjectNode> children = store.list(region, VOLUME, v -> v.path("OpenZFSConfiguration")
                    .path("VolumePath").asText().startsWith(path + "/"));
            List<ObjectNode> snapshots = store.list(region, SNAPSHOT, s -> id.equals(s.path("VolumeId").asText()));
            if (!cascade && (!children.isEmpty() || !snapshots.isEmpty())) {
                throw badRequest("Volume " + id + " has child volumes or snapshots; delete them or use "
                        + "DELETE_CHILD_VOLUMES_AND_SNAPSHOTS.");
            }
            for (ObjectNode child : children) {
                store.list(region, SNAPSHOT, s -> child.path("VolumeId").asText().equals(s.path("VolumeId").asText()))
                        .forEach(s -> store.delete(region, SNAPSHOT, s.path("SnapshotId").asText()));
                store.delete(region, VOLUME, child.path("VolumeId").asText());
            }
            snapshots.forEach(s -> store.delete(region, SNAPSHOT, s.path("SnapshotId").asText()));
        }
        store.transition(region, VOLUME, volume, "Lifecycle", "DELETING", null);
        return response;
    }

    public synchronized ObjectNode describeVolumes(JsonNode input, String region) {
        List<String> ids = strings(input.path("VolumeIds"));
        List<ObjectNode> items = new ArrayList<>();
        if (!ids.isEmpty()) {
            for (String id : ids) {
                items.add(volume(region, id));
            }
        } else {
            for (ObjectNode volume : store.list(region, VOLUME, record -> true)) {
                if (FsxStore.filtered(input.path("Filters"), Map.of("file-system-id",
                        volume.path("FileSystemId").asText(), "storage-virtual-machine-id",
                        volume.path("OntapConfiguration").path("StorageVirtualMachineId").asText("")))) {
                    items.add(volume);
                }
            }
        }
        return store.page(items, input, "Volumes");
    }

    ObjectNode volume(String region, String id) {
        return store.get(region, VOLUME, id).orElseThrow(() -> notFound("VolumeNotFound",
                "Volume " + id + " does not exist."));
    }

    // ---------------------------------------------------------------- snapshots

    public synchronized ObjectNode createSnapshot(JsonNode input, String region) {
        String volumeId = text(input, "VolumeId");
        ObjectNode volume = store.get(region, VOLUME, volumeId).orElseThrow(() -> badRequest(
                "Unable to create the snapshot because the volume was not found."));
        if (!"OPENZFS".equals(volume.path("VolumeType").asText())) {
            throw badRequest("Snapshots are for FSx for OpenZFS volumes.");
        }
        String name = text(input, "Name");
        for (ObjectNode existing : store.list(region, SNAPSHOT, s -> volumeId.equals(s.path("VolumeId").asText()))) {
            if (name.equals(existing.path("Name").asText())) {
                throw badRequest("A snapshot named " + name + " already exists on volume " + volumeId + ".");
            }
        }
        String id = store.id("fsvolsnap");
        ObjectNode snapshot = store.record("SnapshotId", id);
        snapshot.put("ResourceARN", store.arn(region, "snapshot/" + volumeId + "/" + id)).put("Name", name)
                .put("VolumeId", volumeId).put("CreationTime", now()).put("Lifecycle", "CREATING");
        snapshot.set("Tags", input.has("Tags") ? store.tags(input.get("Tags"))
                : volume.path("OpenZFSConfiguration").path("CopyTagsToSnapshots").asBoolean(false)
                ? volume.path("Tags").deepCopy() : store.mapper().createArrayNode());
        store.transition(region, SNAPSHOT, snapshot, "Lifecycle", "CREATING", "AVAILABLE");
        return wrap("Snapshot", snapshot);
    }

    public synchronized ObjectNode describeSnapshots(JsonNode input, String region) {
        List<String> ids = strings(input.path("SnapshotIds"));
        List<ObjectNode> items = new ArrayList<>();
        if (!ids.isEmpty()) {
            for (String id : ids) {
                items.add(store.get(region, SNAPSHOT, id).orElseThrow(() -> notFound("SnapshotNotFound",
                        "Snapshot " + id + " does not exist.")));
            }
        } else {
            for (ObjectNode snapshot : store.list(region, SNAPSHOT, record -> true)) {
                String fileSystemId = store.get(region, VOLUME, snapshot.path("VolumeId").asText())
                        .map(volume -> volume.path("FileSystemId").asText()).orElse("");
                if (FsxStore.filtered(input.path("Filters"), Map.of("volume-id", snapshot.path("VolumeId").asText(),
                        "file-system-id", fileSystemId))) {
                    items.add(snapshot);
                }
            }
        }
        return store.page(items, input, "Snapshots");
    }

    public synchronized ObjectNode updateSnapshot(JsonNode input, String region) {
        String id = text(input, "SnapshotId");
        ObjectNode snapshot = store.get(region, SNAPSHOT, id).orElseThrow(() -> badRequest(
                "Unable to update the snapshot because the snapshot is not found."));
        snapshot.put("Name", text(input, "Name"));
        store.put(region, SNAPSHOT, id, snapshot);
        return wrap("Snapshot", snapshot);
    }

    public synchronized ObjectNode deleteSnapshot(JsonNode input, String region) {
        String id = text(input, "SnapshotId");
        ObjectNode snapshot = store.get(region, SNAPSHOT, id).orElseThrow(() -> notFound("SnapshotNotFound",
                "Snapshot " + id + " does not exist."));
        store.transition(region, SNAPSHOT, snapshot, "Lifecycle", "DELETING", null);
        ObjectNode response = store.mapper().createObjectNode();
        response.put("SnapshotId", id).put("Lifecycle", "DELETING");
        return response;
    }

    /**
     * Rolls a volume back to one of its snapshots; snapshots taken after it must go with
     * {@code DELETE_INTERMEDIATE_SNAPSHOTS}, and volumes cloned from them with {@code DELETE_CLONED_VOLUMES}.
     */
    public synchronized ObjectNode restoreVolumeFromSnapshot(JsonNode input, String region) {
        String snapshotId = text(input, "SnapshotId");
        ObjectNode snapshot = store.get(region, SNAPSHOT, snapshotId).orElseThrow(() -> badRequest(
                "Unable to restore the volume because the snapshot cannot be found."));
        String volumeId = text(input, "VolumeId");
        ObjectNode volume = volume(region, volumeId);
        if (!volumeId.equals(snapshot.path("VolumeId").asText())) {
            throw badRequest("Snapshot " + snapshotId + " is not a snapshot of volume " + volumeId + ".");
        }
        List<String> options = strings(input.path("Options"));
        double taken = snapshot.path("CreationTime").asDouble();
        List<ObjectNode> later = store.list(region, SNAPSHOT, s -> volumeId.equals(s.path("VolumeId").asText())
                && s.path("CreationTime").asDouble() > taken);
        if (!later.isEmpty() && !options.contains("DELETE_INTERMEDIATE_SNAPSHOTS")) {
            throw badRequest("Volume " + volumeId + " has snapshots taken after " + snapshotId
                    + "; restore with DELETE_INTERMEDIATE_SNAPSHOTS to remove them.");
        }
        later.forEach(s -> store.delete(region, SNAPSHOT, s.path("SnapshotId").asText()));
        volume.withObject("/OpenZFSConfiguration").put("RestoreToSnapshot", snapshotId);
        ArrayNode actions = volume.withArray("/AdministrativeActions");
        actions.addObject().put("AdministrativeActionType", "SNAPSHOT_UPDATE").put("Status", "COMPLETED")
                .put("RequestTime", now()).put("ProgressPercent", 100);
        store.put(region, VOLUME, volumeId, volume);
        ObjectNode response = store.mapper().createObjectNode();
        response.put("VolumeId", volumeId).put("Lifecycle", volume.path("Lifecycle").asText());
        response.set("AdministrativeActions", actions.deepCopy());
        return response;
    }

    /** Updates a volume from a snapshot of another, copied as the copy strategy says. */
    public synchronized ObjectNode copySnapshotAndUpdateVolume(JsonNode input, String region) {
        String sourceArn = text(input, "SourceSnapshotARN");
        ObjectNode source = snapshotByArn(region, sourceArn).orElseThrow(() -> badRequest(
                "The SourceSnapshotARN provided is not a valid ARN."));
        String volumeId = text(input, "VolumeId");
        ObjectNode volume = volume(region, volumeId);
        ObjectNode config = volume.withObject("/OpenZFSConfiguration");
        config.put("SourceSnapshotARN", sourceArn).put("CopyStrategy", input.path("CopyStrategy")
                .asText("INCREMENTAL_COPY"));
        ArrayNode actions = volume.withArray("/AdministrativeActions");
        actions.addObject().put("AdministrativeActionType", "VOLUME_UPDATE_WITH_SNAPSHOT").put("Status", "PENDING")
                .put("RequestTime", now()).put("ProgressPercent", 0)
                .put("RemainingTransferBytes", 0).put("TotalTransferBytes", 0);
        meta(volume).put("copiedFrom", source.path("SnapshotId").asText());
        store.transition(region, VOLUME, volume, "Lifecycle", "UPDATING", "AVAILABLE");
        ObjectNode response = store.mapper().createObjectNode();
        response.put("VolumeId", volumeId).put("Lifecycle", volume.path("Lifecycle").asText());
        response.set("AdministrativeActions", volume.path("AdministrativeActions").deepCopy());
        return response;
    }

    private Optional<ObjectNode> snapshotByArn(String region, String arn) {
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(arn);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (!"fsx".equals(parsed.service()) || !parsed.resource().startsWith("snapshot/")
                || !store.account().equals(parsed.accountId())) {
            return Optional.empty();
        }
        String id = parsed.resource().substring(parsed.resource().lastIndexOf('/') + 1);
        return store.get(parsed.region().isEmpty() ? region : parsed.region(), SNAPSHOT, id);
    }

    // ---------------------------------------------------------------- S3 access points

    public synchronized ObjectNode createAndAttachS3AccessPoint(JsonNode input, String region) {
        String name = text(input, "Name");
        String type = text(input, "Type");
        if (store.get(region, ACCESS_POINT, name).isPresent()) {
            throw notFound("AccessPointAlreadyOwnedByYou", "An S3 access point named " + name + " already exists.");
        }
        String field = "OPENZFS".equals(type) ? "OpenZFSConfiguration" : "OntapConfiguration";
        JsonNode config = input.path(field);
        ObjectNode volume = volume(region, text(config, "VolumeId"));
        if (!type.equals(volume.path("VolumeType").asText())) {
            throw badRequest("Volume " + volume.path("VolumeId").asText() + " is not an " + type + " volume.");
        }
        ObjectNode attachment = store.record("Name", name);
        attachment.put("Lifecycle", "CREATING").put("CreationTime", now()).put("Type", type);
        attachment.set(field, config.deepCopy());
        ObjectNode accessPoint = attachment.putObject("S3AccessPoint");
        accessPoint.put("ResourceARN", AwsArnUtils.Arn.of("s3", region, store.account(), "accesspoint/" + name)
                .toString()).put("Alias", name + "-" + store.random("abcdefghijklmnopqrstuvwxyz0123456789", 34)
                + "-ext-s3alias");
        if (input.path("S3AccessPoint").has("VpcConfiguration")) {
            accessPoint.set("VpcConfiguration", input.path("S3AccessPoint").get("VpcConfiguration").deepCopy());
        }
        if (input.path("S3AccessPoint").hasNonNull("Policy")) {
            meta(attachment).put("policy", input.path("S3AccessPoint").get("Policy").asText());
        }
        store.transition(region, ACCESS_POINT, attachment, "Lifecycle", "CREATING", "AVAILABLE");
        return wrap("S3AccessPointAttachment", attachment);
    }

    public synchronized ObjectNode describeS3AccessPointAttachments(JsonNode input, String region) {
        List<String> names = strings(input.path("Names"));
        List<ObjectNode> items = new ArrayList<>();
        if (!names.isEmpty()) {
            for (String name : names) {
                items.add(store.get(region, ACCESS_POINT, name).orElseThrow(() -> notFound(
                        "S3AccessPointAttachmentNotFound", "S3 access point attachment " + name + " not found.")));
            }
        } else {
            for (ObjectNode attachment : store.list(region, ACCESS_POINT, record -> true)) {
                String field = "OPENZFS".equals(attachment.path("Type").asText()) ? "OpenZFSConfiguration"
                        : "OntapConfiguration";
                String volumeId = attachment.path(field).path("VolumeId").asText();
                String fileSystemId = store.get(region, VOLUME, volumeId).map(v -> v.path("FileSystemId").asText())
                        .orElse("");
                if (FsxStore.filtered(input.path("Filters"), Map.of("file-system-id", fileSystemId, "volume-id",
                        volumeId, "type", attachment.path("Type").asText()))) {
                    items.add(attachment);
                }
            }
        }
        return store.page(items, input, "S3AccessPointAttachments");
    }

    public synchronized ObjectNode detachAndDeleteS3AccessPoint(JsonNode input, String region) {
        String name = text(input, "Name");
        ObjectNode attachment = store.get(region, ACCESS_POINT, name).orElseThrow(() -> notFound(
                "S3AccessPointAttachmentNotFound", "S3 access point attachment " + name + " not found."));
        store.transition(region, ACCESS_POINT, attachment, "Lifecycle", "DELETING", null);
        ObjectNode response = store.mapper().createObjectNode();
        response.put("Lifecycle", "DELETING").put("Name", name);
        return response;
    }

    private ObjectNode wrap(String field, ObjectNode record) {
        ObjectNode response = store.mapper().createObjectNode();
        response.set(field, view(record));
        return response;
    }
}
