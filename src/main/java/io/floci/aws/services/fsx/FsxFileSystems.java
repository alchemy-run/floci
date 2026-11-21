package io.floci.aws.services.fsx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.AwsRegions;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.ec2.model.NetworkInterface;
import io.floci.aws.services.ec2.model.SecurityGroup;
import io.floci.aws.services.ec2.model.Subnet;
import io.floci.aws.services.kms.KmsService;
import io.floci.aws.services.s3.S3Service;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static io.floci.aws.services.fsx.FsxStore.BACKUP;
import static io.floci.aws.services.fsx.FsxStore.CACHE;
import static io.floci.aws.services.fsx.FsxStore.FILE_SYSTEM;
import static io.floci.aws.services.fsx.FsxStore.SVM;
import static io.floci.aws.services.fsx.FsxStore.VOLUME;
import static io.floci.aws.services.fsx.FsxStore.badRequest;
import static io.floci.aws.services.fsx.FsxStore.incompatible;
import static io.floci.aws.services.fsx.FsxStore.meta;
import static io.floci.aws.services.fsx.FsxStore.notFound;
import static io.floci.aws.services.fsx.FsxStore.now;
import static io.floci.aws.services.fsx.FsxStore.optional;
import static io.floci.aws.services.fsx.FsxStore.strings;
import static io.floci.aws.services.fsx.FsxStore.text;
import static io.floci.aws.services.fsx.FsxStore.view;

/**
 * FSx file systems of the four types, Lustre file caches, DNS aliases, backups and the account's shared VPC
 * setting. A file system or cache is placed in its subnets, which must share a VPC, with a network interface in
 * each and the security groups given; it is CREATING, then AVAILABLE, a transition apart. Each type's storage
 * capacity, throughput and subnet count are checked as FSx checks them.
 */
@ApplicationScoped
public class FsxFileSystems {

    private static final Logger LOG = Logger.getLogger(FsxFileSystems.class);
    private static final String LOWER = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final Set<Integer> WINDOWS_THROUGHPUT = Set.of(8, 16, 32, 64, 128, 256, 512, 1024, 2048, 4608,
            6144, 9216, 12288);
    private static final Set<Integer> ONTAP_THROUGHPUT = Set.of(128, 256, 384, 512, 768, 1024, 1536, 2048, 3072,
            4096, 6144);
    private static final Set<Integer> OPENZFS_THROUGHPUT = Set.of(64, 128, 160, 256, 320, 384, 512, 640, 768, 1024,
            1280, 1536, 2048, 2560, 3072, 3840, 4096, 5120, 6144, 7680, 10240);

    private final FsxStore store;
    private final Ec2Service ec2;
    private final Instance<KmsService> kms;
    private final Instance<S3Service> s3;
    private final Instance<FsxVolumes> volumes;
    private final Instance<FsxRepositories> repositories;

    @Inject
    public FsxFileSystems(FsxStore store, Ec2Service ec2, Instance<KmsService> kms, Instance<S3Service> s3,
                          Instance<FsxVolumes> volumes, Instance<FsxRepositories> repositories) {
        this.store = store;
        this.ec2 = ec2;
        this.kms = kms;
        this.s3 = s3;
        this.volumes = volumes;
        this.repositories = repositories;
    }

    // ---------------------------------------------------------------- file systems

    public synchronized ObjectNode createFileSystem(JsonNode input, String region) {
        Optional<ObjectNode> repeat = byToken(region, FILE_SYSTEM, input);
        if (repeat.isPresent()) {
            return wrap("FileSystem", repeat.get());
        }
        String type = text(input, "FileSystemType");
        int capacity = input.path("StorageCapacity").asInt(0);
        String storageType = input.path("StorageType").asText("SSD");
        String id = store.id("fs");
        ObjectNode fileSystem = store.record("FileSystemId", id);
        fileSystem.put("OwnerId", store.account()).put("CreationTime", now()).put("FileSystemType", type)
                .put("Lifecycle", "CREATING");
        List<String> subnets = strings(input.path("SubnetIds"));
        switch (type) {
            case "LUSTRE" -> lustre(fileSystem, input.path("LustreConfiguration"), capacity, storageType, subnets,
                    region);
            case "WINDOWS" -> windows(fileSystem, input.path("WindowsConfiguration"), capacity, storageType,
                    subnets);
            case "ONTAP" -> ontap(fileSystem, input.path("OntapConfiguration"), capacity, subnets, region);
            case "OPENZFS" -> openZfs(fileSystem, input.path("OpenZFSConfiguration"), capacity, subnets, region);
            default -> throw badRequest("FileSystemType " + type + " is not valid.");
        }
        fileSystem.put("StorageCapacity", capacity).put("StorageType", storageType);
        if (input.hasNonNull("FileSystemTypeVersion")) {
            fileSystem.put("FileSystemTypeVersion", input.get("FileSystemTypeVersion").asText());
        } else if ("LUSTRE".equals(type)) {
            fileSystem.put("FileSystemTypeVersion", "2.15");
        }
        place(region, fileSystem, subnets, strings(input.path("SecurityGroupIds")), "File System " + id);
        String kmsKey = kmsKey(input, region, !isScratch(fileSystem));
        if (kmsKey != null) {
            fileSystem.put("KmsKeyId", kmsKey);
        }
        fileSystem.put("ResourceARN", store.arn(region, "file-system/" + id));
        fileSystem.set("Tags", store.tags(input.get("Tags")));
        if (input.hasNonNull("ClientRequestToken")) {
            meta(fileSystem).put("token", input.get("ClientRequestToken").asText());
        }
        store.transition(region, FILE_SYSTEM, fileSystem, "Lifecycle", "CREATING", "AVAILABLE");
        if ("OPENZFS".equals(type) && volumes.isResolvable()) {
            volumes.get().rootVolume(region, fileSystem, input.path("OpenZFSConfiguration")
                    .path("RootVolumeConfiguration"));
        }
        LOG.infov("FSx {0} file system {1} created in {2}", type, id, region);
        return wrap("FileSystem", fileSystem);
    }

    public synchronized ObjectNode createFileSystemFromBackup(JsonNode input, String region) {
        String backupId = text(input, "BackupId");
        ObjectNode backup = store.get(region, BACKUP, backupId).orElseThrow(() -> notFound("BackupNotFound",
                "Backup " + backupId + " does not exist."));
        if (!"AVAILABLE".equals(backup.path("Lifecycle").asText())) {
            throw notFound("BackupRestoring", "Backup " + backupId + " is " + backup.path("Lifecycle").asText()
                    + " and cannot be restored.");
        }
        JsonNode source = backup.path("FileSystem");
        ObjectNode request = input.deepCopy();
        request.put("FileSystemType", source.path("FileSystemType").asText());
        if (!request.hasNonNull("StorageCapacity")) {
            request.put("StorageCapacity", source.path("StorageCapacity").asInt());
        }
        request.put("StorageType", input.path("StorageType").asText(source.path("StorageType").asText("SSD")));
        String typeField = configField(source.path("FileSystemType").asText());
        if (!request.has(typeField) && source.has(typeField.replace("Configuration", "Configuration"))) {
            ObjectNode config = source.path(typeField).deepCopy();
            config.remove(List.of("MountName", "DataRepositoryConfiguration", "RootVolumeId", "Endpoints",
                    "RemoteAdministrationEndpoint", "PreferredFileServerIp", "MaintenanceOperationsInProgress",
                    "Aliases"));
            request.set(typeField, config);
        }
        request.remove("BackupId");
        ObjectNode response = createFileSystem(request, region);
        String id = response.path("FileSystem").path("FileSystemId").asText();
        store.get(region, FILE_SYSTEM, id).ifPresent(fs -> {
            meta(fs).put("restoredFrom", backupId);
            store.put(region, FILE_SYSTEM, id, fs);
        });
        return response;
    }

    public synchronized ObjectNode describeFileSystems(JsonNode input, String region) {
        List<String> ids = strings(input.path("FileSystemIds"));
        List<ObjectNode> items = new ArrayList<>();
        for (String id : ids) {
            items.add(fileSystem(region, id));
        }
        if (ids.isEmpty()) {
            items.addAll(store.list(region, FILE_SYSTEM, fs -> true));
        }
        return store.page(items, input, "FileSystems");
    }

    /** Updates a file system's settings in place: it is UPDATING, with an administrative action, until done. */
    public synchronized ObjectNode updateFileSystem(JsonNode input, String region) {
        String id = text(input, "FileSystemId");
        ObjectNode fileSystem = fileSystem(region, id);
        if (!"AVAILABLE".equals(fileSystem.path("Lifecycle").asText())) {
            throw badRequest("File system " + id + " is " + fileSystem.path("Lifecycle").asText()
                    + " and cannot be updated.");
        }
        String type = fileSystem.path("FileSystemType").asText();
        List<String> actions = new ArrayList<>();
        if (input.hasNonNull("StorageCapacity")) {
            int capacity = input.get("StorageCapacity").asInt();
            if (isScratch(fileSystem)) {
                throw badRequest("The storage capacity of a scratch file system cannot be changed.");
            }
            if (capacity <= fileSystem.path("StorageCapacity").asInt()) {
                throw badRequest("The new storage capacity must be greater than the current one.");
            }
            if (capacity < fileSystem.path("StorageCapacity").asInt() * 1.1) {
                throw badRequest("The new storage capacity must be at least 10 percent greater than the current one.");
            }
            fileSystem.put("StorageCapacity", capacity);
            actions.add("FILE_SYSTEM_UPDATE");
            actions.add("STORAGE_OPTIMIZATION");
        }
        String field = configField(type);
        JsonNode update = input.path(field);
        if (!update.isMissingNode()) {
            ObjectNode config = fileSystem.withObject("/" + field);
            update.fields().forEachRemaining(entry -> {
                if (!Set.of("SelfManagedActiveDirectoryConfiguration", "AddRouteTableIds", "RemoveRouteTableIds",
                        "FsxAdminPassword").contains(entry.getKey())) {
                    config.set(entry.getKey(), entry.getValue().deepCopy());
                }
            });
            ArrayNode routes = config.withArray("/RouteTableIds");
            Set<String> current = new LinkedHashSet<>(strings(routes));
            current.addAll(strings(update.path("AddRouteTableIds")));
            strings(update.path("RemoveRouteTableIds")).forEach(current::remove);
            routes.removeAll();
            current.forEach(routes::add);
            if (routes.isEmpty() && !Set.of("ONTAP", "OPENZFS").contains(type)) {
                config.remove("RouteTableIds");
            }
            if (update.has("ThroughputCapacity")) {
                validateThroughput(type, update.get("ThroughputCapacity").asInt());
            }
            actions.add("FILE_SYSTEM_UPDATE");
        }
        if (actions.isEmpty()) {
            throw badRequest("The request has nothing to update.");
        }
        ArrayNode administrative = fileSystem.withArray("/AdministrativeActions");
        for (String action : new LinkedHashSet<>(actions)) {
            administrative.addObject().put("AdministrativeActionType", action).put("Status", "IN_PROGRESS")
                    .put("RequestTime", now()).put("ProgressPercent", 0);
        }
        store.transition(region, FILE_SYSTEM, fileSystem, "Lifecycle", "UPDATING", "AVAILABLE");
        return wrap("FileSystem", fileSystem);
    }

    /**
     * Deletes a file system: it is DELETING until it is gone, its network interfaces released and, unless skipped,
     * a final backup taken. An ONTAP file system with storage virtual machines, or an OpenZFS one with child
     * volumes, cannot be deleted.
     */
    public synchronized ObjectNode deleteFileSystem(JsonNode input, String region) {
        String id = text(input, "FileSystemId");
        ObjectNode fileSystem = fileSystem(region, id);
        String type = fileSystem.path("FileSystemType").asText();
        if ("DELETING".equals(fileSystem.path("Lifecycle").asText())) {
            return deleteResponse(fileSystem, null, type);
        }
        if ("ONTAP".equals(type) && !store.list(region, SVM, svm -> id.equals(svm.path("FileSystemId").asText()))
                .isEmpty()) {
            throw badRequest("File system " + id + " has storage virtual machines; delete them first.");
        }
        if ("OPENZFS".equals(type) && store.list(region, VOLUME, volume -> id.equals(volume.path("FileSystemId")
                .asText()) && volume.path("OpenZFSConfiguration").hasNonNull("ParentVolumeId")).size() > 0) {
            throw badRequest("File system " + id + " has child volumes; delete them first.");
        }
        String field = type.charAt(0) + type.substring(1).toLowerCase(Locale.ROOT);
        JsonNode options = input.path("WINDOWS".equals(type) ? "WindowsConfiguration" : "LUSTRE".equals(type)
                ? "LustreConfiguration" : "OPENZFS".equals(type) ? "OpenZFSConfiguration" : "OntapConfiguration");
        boolean skip = options.path("SkipFinalBackup").asBoolean(!"WINDOWS".equals(type) && !"OPENZFS".equals(type))
                || isScratch(fileSystem) || "ONTAP".equals(type);
        String finalBackup = null;
        if (!skip) {
            finalBackup = backupOf(region, fileSystem, null, "USER_INITIATED", options.get("FinalBackupTags"));
        }
        releaseInterfaces(region, fileSystem);
        if ("OPENZFS".equals(type)) {
            for (ObjectNode volume : store.list(region, VOLUME, v -> id.equals(v.path("FileSystemId").asText()))) {
                store.delete(region, VOLUME, volume.path("VolumeId").asText());
            }
        }
        store.transition(region, FILE_SYSTEM, fileSystem, "Lifecycle", "DELETING", null);
        LOG.debugv("Deleting FSx {0} {1} ({2})", field, id, region);
        return deleteResponse(fileSystem, finalBackup, type);
    }

    private ObjectNode deleteResponse(ObjectNode fileSystem, String finalBackup, String type) {
        ObjectNode response = store.mapper().createObjectNode();
        response.put("FileSystemId", fileSystem.path("FileSystemId").asText()).put("Lifecycle", "DELETING");
        if (!"ONTAP".equals(type)) {
            String field = "WINDOWS".equals(type) ? "WindowsResponse" : "LUSTRE".equals(type) ? "LustreResponse"
                    : "OpenZFSResponse";
            ObjectNode out = response.putObject(field);
            if (finalBackup != null) {
                out.put("FinalBackupId", finalBackup);
            }
            out.putArray("FinalBackupTags");
        }
        return response;
    }

    public synchronized ObjectNode releaseFileSystemNfsV3Locks(JsonNode input, String region) {
        ObjectNode fileSystem = fileSystem(region, text(input, "FileSystemId"));
        if (!"OPENZFS".equals(fileSystem.path("FileSystemType").asText())) {
            throw badRequest("NFS v3 locks can only be released on OpenZFS file systems.");
        }
        fileSystem.withArray("/AdministrativeActions").addObject().put("AdministrativeActionType",
                "RELEASE_NFS_V3_LOCKS").put("Status", "COMPLETED").put("RequestTime", now()).put("ProgressPercent", 100);
        store.put(region, FILE_SYSTEM, fileSystem.path("FileSystemId").asText(), fileSystem);
        return wrap("FileSystem", fileSystem);
    }

    /** Starts recovering a MISCONFIGURED file system: it is UPDATING until it is AVAILABLE again. */
    public synchronized ObjectNode startMisconfiguredStateRecovery(JsonNode input, String region) {
        ObjectNode fileSystem = fileSystem(region, text(input, "FileSystemId"));
        String state = fileSystem.path("Lifecycle").asText();
        if (!state.startsWith("MISCONFIGURED")) {
            throw badRequest("File system " + fileSystem.path("FileSystemId").asText() + " is " + state
                    + ", not MISCONFIGURED.");
        }
        fileSystem.withArray("/AdministrativeActions").addObject().put("AdministrativeActionType",
                "MISCONFIGURED_STATE_RECOVERY").put("Status", "IN_PROGRESS").put("RequestTime", now());
        store.transition(region, FILE_SYSTEM, fileSystem, "Lifecycle", "UPDATING", "AVAILABLE");
        return wrap("FileSystem", fileSystem);
    }

    ObjectNode fileSystem(String region, String id) {
        return store.get(region, FILE_SYSTEM, id).orElseThrow(() -> notFound("FileSystemNotFound",
                "File system '" + id + "' does not exist."));
    }

    // ---------------------------------------------------------------- types

    private void lustre(ObjectNode fileSystem, JsonNode config, int capacity, String storageType,
                        List<String> subnets, String region) {
        String deployment = config.path("DeploymentType").asText("SCRATCH_1");
        if (subnets.size() != 1) {
            throw new AwsException("InvalidNetworkSettings", "Lustre file systems take exactly one subnet.", 400,
                    Map.of("InvalidSubnetId", String.join(",", subnets)));
        }
        Integer throughput = config.hasNonNull("PerUnitStorageThroughput")
                ? config.get("PerUnitStorageThroughput").asInt() : null;
        switch (deployment) {
            case "SCRATCH_1" -> require(capacity == 1200 || capacity == 2400 || (capacity > 0 && capacity % 3600 == 0),
                    "SCRATCH_1 storage capacity must be 1200, 2400 or a multiple of 3600 GiB.");
            case "SCRATCH_2" -> require(capacity == 1200 || (capacity > 0 && capacity % 2400 == 0),
                    "SCRATCH_2 storage capacity must be 1200 or a multiple of 2400 GiB.");
            case "PERSISTENT_1" -> {
                if ("HDD".equals(storageType)) {
                    require(throughput != null && (throughput == 12 || throughput == 40),
                            "PERSISTENT_1 HDD PerUnitStorageThroughput must be 12 or 40.");
                    int step = throughput == 12 ? 6000 : 1800;
                    require(capacity > 0 && capacity % step == 0, "PERSISTENT_1 HDD storage capacity must be a "
                            + "multiple of " + step + " GiB.");
                } else {
                    require(throughput != null && Set.of(50, 100, 200).contains(throughput),
                            "PERSISTENT_1 SSD PerUnitStorageThroughput must be 50, 100 or 200.");
                    require(capacity == 1200 || (capacity > 0 && capacity % 2400 == 0),
                            "PERSISTENT_1 storage capacity must be 1200 or a multiple of 2400 GiB.");
                }
            }
            case "PERSISTENT_2" -> {
                if (!"INTELLIGENT_TIERING".equals(storageType)) {
                    require(throughput != null && Set.of(125, 250, 500, 1000).contains(throughput),
                            "PERSISTENT_2 PerUnitStorageThroughput must be 125, 250, 500 or 1000.");
                    require(capacity == 1200 || (capacity > 0 && capacity % 2400 == 0),
                            "PERSISTENT_2 storage capacity must be 1200 or a multiple of 2400 GiB.");
                }
            }
            default -> throw badRequest("Lustre DeploymentType " + deployment + " is not valid.");
        }
        if (deployment.startsWith("SCRATCH") && throughput != null) {
            throw new AwsException("InvalidPerUnitStorageThroughput", "Scratch file systems take no "
                    + "PerUnitStorageThroughput.", 400);
        }
        if (!"SSD".equals(storageType) && !deployment.startsWith("PERSISTENT")) {
            throw incompatible("StorageType", storageType + " storage requires a persistent deployment type.");
        }
        ObjectNode out = fileSystem.putObject("LustreConfiguration");
        out.put("WeeklyMaintenanceStartTime", config.path("WeeklyMaintenanceStartTime").asText("1:05:00"))
                .put("DeploymentType", deployment)
                .put("MountName", "SCRATCH_1".equals(deployment) ? "fsx" : store.random(LOWER, 8));
        if (throughput != null) {
            out.put("PerUnitStorageThroughput", throughput);
        }
        String importPath = optional(config, "ImportPath");
        if (importPath != null) {
            if (deployment.equals("PERSISTENT_2")) {
                throw badRequest("PERSISTENT_2 file systems link S3 through data repository associations, not "
                        + "ImportPath.");
            }
            bucket(importPath, "InvalidImportPath");
            String exportPath = config.path("ExportPath").asText(importPath + "/FSxLustre" + timestamp());
            bucket(exportPath, "InvalidExportPath");
            ObjectNode repository = out.putObject("DataRepositoryConfiguration");
            repository.put("Lifecycle", "AVAILABLE").put("ImportPath", importPath).put("ExportPath", exportPath)
                    .put("ImportedFileChunkSize", config.path("ImportedFileChunkSize").asInt(1024))
                    .put("AutoImportPolicy", config.path("AutoImportPolicy").asText("NONE"));
        }
        if (deployment.startsWith("PERSISTENT")) {
            out.put("DailyAutomaticBackupStartTime", config.path("DailyAutomaticBackupStartTime").asText("05:00"))
                    .put("AutomaticBackupRetentionDays", config.path("AutomaticBackupRetentionDays").asInt(0))
                    .put("CopyTagsToBackups", config.path("CopyTagsToBackups").asBoolean(false));
        }
        out.put("DataCompressionType", config.path("DataCompressionType").asText("NONE"));
        for (String field : List.of("DriveCacheType", "LogConfiguration", "RootSquashConfiguration",
                "MetadataConfiguration", "EfaEnabled", "ThroughputCapacity", "DataReadCacheConfiguration")) {
            if (config.has(field)) {
                out.set(field, config.get(field).deepCopy());
            }
        }
        if (!out.has("LogConfiguration")) {
            out.putObject("LogConfiguration").put("Level", "DISABLED");
        }
        fileSystem.put("DNSName", fileSystem.path("FileSystemId").asText() + ".fsx." + region + "."
                + AwsRegions.dnsSuffixFor(region));
    }

    private void windows(ObjectNode fileSystem, JsonNode config, int capacity, String storageType,
                         List<String> subnets) {
        String deployment = config.path("DeploymentType").asText("SINGLE_AZ_1");
        int throughput = config.path("ThroughputCapacity").asInt(0);
        validateThroughput("WINDOWS", throughput);
        require("HDD".equals(storageType) ? capacity >= 2000 && capacity <= 65536 : capacity >= 32
                && capacity <= 65536, "Windows storage capacity must be 32-65536 GiB on SSD and 2000-65536 on HDD.");
        boolean multiAz = "MULTI_AZ_1".equals(deployment);
        if (subnets.size() != (multiAz ? 2 : 1)) {
            throw new AwsException("InvalidNetworkSettings", deployment + " takes " + (multiAz ? "two subnets"
                    : "one subnet") + ".", 400);
        }
        String domain;
        ObjectNode out = fileSystem.putObject("WindowsConfiguration");
        if (config.hasNonNull("ActiveDirectoryId")) {
            out.put("ActiveDirectoryId", config.get("ActiveDirectoryId").asText());
            domain = config.get("ActiveDirectoryId").asText() + ".directory";
        } else if (config.hasNonNull("SelfManagedActiveDirectoryConfiguration")) {
            JsonNode ad = config.get("SelfManagedActiveDirectoryConfiguration");
            domain = text(ad, "DomainName");
            ObjectNode attributes = out.putObject("SelfManagedActiveDirectoryConfiguration");
            attributes.put("DomainName", domain);
            for (String field : List.of("OrganizationalUnitDistinguishedName", "FileSystemAdministratorsGroup",
                    "UserName", "DomainJoinServiceAccountSecret")) {
                if (ad.hasNonNull(field)) {
                    attributes.put(field, ad.get(field).asText());
                }
            }
            attributes.set("DnsIps", ad.path("DnsIps").deepCopy());
        } else {
            throw new AwsException("MissingFileSystemConfiguration", "A Windows file system joins an Active "
                    + "Directory: give ActiveDirectoryId or SelfManagedActiveDirectoryConfiguration.", 400);
        }
        String host = "amznfsx" + store.random(LOWER, 8);
        out.put("DeploymentType", deployment).put("ThroughputCapacity", throughput)
                .put("RemoteAdministrationEndpoint", host + "." + domain)
                .put("WeeklyMaintenanceStartTime", config.path("WeeklyMaintenanceStartTime").asText("1:05:00"))
                .put("DailyAutomaticBackupStartTime", config.path("DailyAutomaticBackupStartTime").asText("05:00"))
                .put("AutomaticBackupRetentionDays", config.path("AutomaticBackupRetentionDays").asInt(7))
                .put("CopyTagsToBackups", config.path("CopyTagsToBackups").asBoolean(false));
        out.putArray("MaintenanceOperationsInProgress");
        if (multiAz) {
            String preferred = text(config, "PreferredSubnetId");
            if (!subnets.contains(preferred)) {
                throw new AwsException("InvalidNetworkSettings", "PreferredSubnetId must be one of SubnetIds.", 400);
            }
            out.put("PreferredSubnetId", preferred);
        }
        ArrayNode aliases = out.putArray("Aliases");
        for (String alias : strings(config.path("Aliases"))) {
            aliases.addObject().put("Name", alias.toLowerCase(Locale.ROOT)).put("Lifecycle", "AVAILABLE");
        }
        for (String field : List.of("AuditLogConfiguration", "DiskIopsConfiguration", "FsrmConfiguration")) {
            if (config.has(field)) {
                out.set(field, config.get(field).deepCopy());
            }
        }
        fileSystem.put("DNSName", host + "." + domain);
    }

    private void ontap(ObjectNode fileSystem, JsonNode config, int capacity, List<String> subnets, String region) {
        String deployment = text(config, "DeploymentType");
        require(capacity >= 1024 && capacity <= 1048576, "ONTAP storage capacity must be 1024-1048576 GiB.");
        boolean multiAz = deployment.startsWith("MULTI_AZ");
        if (subnets.size() != (multiAz ? 2 : 1)) {
            throw new AwsException("InvalidNetworkSettings", deployment + " takes " + (multiAz ? "two subnets"
                    : "one subnet") + ".", 400);
        }
        Integer throughput = config.hasNonNull("ThroughputCapacity") ? config.get("ThroughputCapacity").asInt()
                : null;
        Integer perPair = config.hasNonNull("ThroughputCapacityPerHAPair")
                ? config.get("ThroughputCapacityPerHAPair").asInt() : null;
        if (throughput == null && perPair == null) {
            throw badRequest("ONTAP file systems need ThroughputCapacity or ThroughputCapacityPerHAPair.");
        }
        int pairs = config.path("HAPairs").asInt(1);
        if (throughput != null) {
            validateThroughput("ONTAP", throughput / pairs);
        }
        String id = fileSystem.path("FileSystemId").asText();
        ObjectNode out = fileSystem.putObject("OntapConfiguration");
        out.put("DeploymentType", deployment)
                .put("AutomaticBackupRetentionDays", config.path("AutomaticBackupRetentionDays").asInt(30))
                .put("DailyAutomaticBackupStartTime", config.path("DailyAutomaticBackupStartTime").asText("05:00"))
                .put("WeeklyMaintenanceStartTime", config.path("WeeklyMaintenanceStartTime").asText("1:05:00"))
                .put("ThroughputCapacity", throughput != null ? throughput : perPair * pairs)
                .put("ThroughputCapacityPerHAPair", perPair != null ? perPair : throughput / pairs)
                .put("HAPairs", pairs);
        if (multiAz) {
            String preferred = text(config, "PreferredSubnetId");
            out.put("PreferredSubnetId", preferred).put("EndpointIpAddressRange",
                    config.path("EndpointIpAddressRange").asText("198.19.255.0/24"));
            out.set("RouteTableIds", config.path("RouteTableIds").deepCopy());
        }
        out.putObject("DiskIopsConfiguration").put("Mode", config.path("DiskIopsConfiguration").path("Mode")
                .asText("AUTOMATIC")).put("Iops", config.path("DiskIopsConfiguration").path("Iops")
                .asLong(capacity * 3L));
        String suffix = "." + id + ".fsx." + region + "." + AwsRegions.dnsSuffixFor(region);
        ObjectNode endpoints = out.putObject("Endpoints");
        endpoints.putObject("Management").put("DNSName", "management" + suffix).putArray("IpAddresses");
        endpoints.putObject("Intercluster").put("DNSName", "intercluster" + suffix).putArray("IpAddresses");
        fileSystem.put("DNSName", id + ".fsx." + region + "." + AwsRegions.dnsSuffixFor(region));
    }

    private void openZfs(ObjectNode fileSystem, JsonNode config, int capacity, List<String> subnets,
                         String region) {
        String deployment = text(config, "DeploymentType");
        require(capacity >= 64 && capacity <= 524288, "OpenZFS storage capacity must be 64-524288 GiB.");
        boolean multiAz = "MULTI_AZ_1".equals(deployment);
        if (subnets.size() != (multiAz ? 2 : 1)) {
            throw new AwsException("InvalidNetworkSettings", deployment + " takes " + (multiAz ? "two subnets"
                    : "one subnet") + ".", 400);
        }
        int throughput = config.path("ThroughputCapacity").asInt(0);
        validateThroughput("OPENZFS", throughput);
        ObjectNode out = fileSystem.putObject("OpenZFSConfiguration");
        out.put("DeploymentType", deployment).put("ThroughputCapacity", throughput)
                .put("AutomaticBackupRetentionDays", config.path("AutomaticBackupRetentionDays").asInt(30))
                .put("CopyTagsToBackups", config.path("CopyTagsToBackups").asBoolean(false))
                .put("CopyTagsToVolumes", config.path("CopyTagsToVolumes").asBoolean(false))
                .put("DailyAutomaticBackupStartTime", config.path("DailyAutomaticBackupStartTime").asText("05:00"))
                .put("WeeklyMaintenanceStartTime", config.path("WeeklyMaintenanceStartTime").asText("1:05:00"));
        out.putObject("DiskIopsConfiguration").put("Mode", config.path("DiskIopsConfiguration").path("Mode")
                .asText("AUTOMATIC")).put("Iops", config.path("DiskIopsConfiguration").path("Iops")
                .asLong(capacity * 3L));
        if (multiAz) {
            out.put("PreferredSubnetId", text(config, "PreferredSubnetId"));
            out.set("RouteTableIds", config.path("RouteTableIds").deepCopy());
        }
        if (config.has("ReadCacheConfiguration")) {
            out.set("ReadCacheConfiguration", config.get("ReadCacheConfiguration").deepCopy());
        }
        fileSystem.put("DNSName", fileSystem.path("FileSystemId").asText() + ".fsx." + region + "."
                + AwsRegions.dnsSuffixFor(region));
    }

    private static void validateThroughput(String type, int throughput) {
        Set<Integer> allowed = switch (type) {
            case "WINDOWS" -> WINDOWS_THROUGHPUT;
            case "ONTAP" -> ONTAP_THROUGHPUT;
            case "OPENZFS" -> OPENZFS_THROUGHPUT;
            default -> null;
        };
        if (allowed != null && !allowed.contains(throughput)) {
            throw badRequest("ThroughputCapacity " + throughput + " is not valid for " + type + " file systems.");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw badRequest(message);
        }
    }

    static boolean isScratch(ObjectNode fileSystem) {
        return fileSystem.path("LustreConfiguration").path("DeploymentType").asText("").startsWith("SCRATCH");
    }

    static String configField(String type) {
        return switch (type) {
            case "WINDOWS" -> "WindowsConfiguration";
            case "LUSTRE" -> "LustreConfiguration";
            case "ONTAP" -> "OntapConfiguration";
            default -> "OpenZFSConfiguration";
        };
    }

    private void bucket(String s3Path, String code) {
        if (!s3Path.startsWith("s3://")) {
            throw new AwsException(code, s3Path + " is not an S3 path.", 400);
        }
        String bucket = s3Path.substring(5).split("/", 2)[0];
        if (s3.isResolvable()) {
            try {
                s3.get().listObjects(bucket, "", null, 1);
            } catch (AwsException e) {
                throw new AwsException(code, "The S3 bucket " + bucket + " does not exist.", 400);
            }
        }
    }

    private static String timestamp() {
        return DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC).format(Instant.now());
    }

    private String kmsKey(JsonNode input, String region, boolean encrypted) {
        String given = optional(input, "KmsKeyId");
        if (!kms.isResolvable()) {
            return given;
        }
        if (given != null) {
            try {
                return kms.get().describeKey(given, region).getArn();
            } catch (AwsException e) {
                throw new AwsException("InvalidDestinationKmsKey", "The KMS key " + given + " is not valid.", 400);
            }
        }
        if (!encrypted) {
            return null;
        }
        try {
            return kms.get().describeKey("alias/aws/fsx", region).getArn();
        } catch (AwsException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- network

    /** Places a file system or cache in its subnets: one network interface in each, with its security groups. */
    void place(String region, ObjectNode record, List<String> subnetIds, List<String> groupIds, String owner) {
        if (subnetIds.isEmpty()) {
            throw new AwsException("InvalidNetworkSettings", "SubnetIds is required.", 400);
        }
        List<Subnet> subnets;
        try {
            subnets = ec2.describeSubnets(region, subnetIds, Map.of());
        } catch (AwsException e) {
            throw new AwsException("InvalidNetworkSettings", "Invalid subnet ID(s): " + String.join(", ", subnetIds),
                    400, Map.of("InvalidSubnetId", subnetIds.get(0)));
        }
        if (subnets.size() != new LinkedHashSet<>(subnetIds).size()) {
            throw new AwsException("InvalidNetworkSettings", "Invalid subnet ID(s): " + String.join(", ", subnetIds),
                    400, Map.of("InvalidSubnetId", subnetIds.get(0)));
        }
        String vpcId = subnets.get(0).getVpcId();
        for (Subnet subnet : subnets) {
            if (!vpcId.equals(subnet.getVpcId())) {
                throw new AwsException("InvalidNetworkSettings", "The subnets must be in one VPC.", 400);
            }
        }
        if (!groupIds.isEmpty()) {
            List<SecurityGroup> groups;
            try {
                groups = ec2.describeSecurityGroups(region, groupIds, List.of(), Map.of());
            } catch (AwsException e) {
                throw new AwsException("InvalidNetworkSettings", "Invalid security group ID(s): "
                        + String.join(", ", groupIds), 400, Map.of("InvalidSecurityGroupId", groupIds.get(0)));
            }
            for (SecurityGroup group : groups) {
                if (!vpcId.equals(group.getVpcId())) {
                    throw new AwsException("InvalidNetworkSettings", "Security group " + group.getGroupId()
                            + " is not in VPC " + vpcId + ".", 400, Map.of("InvalidSecurityGroupId",
                            group.getGroupId()));
                }
            }
        }
        record.put("VpcId", vpcId);
        ArrayNode subnetsOut = record.putArray("SubnetIds");
        ArrayNode interfaces = record.putArray("NetworkInterfaceIds");
        for (String subnetId : subnetIds) {
            subnetsOut.add(subnetId);
            NetworkInterface eni = ec2.createNetworkInterface(region, subnetId, "ENI for FSx " + owner, null, null,
                    groupIds.isEmpty() ? null : groupIds, null);
            interfaces.add(eni.getNetworkInterfaceId());
            ObjectNode endpoints = record.path("OntapConfiguration").path("Endpoints").isObject()
                    ? (ObjectNode) record.path("OntapConfiguration").path("Endpoints") : null;
            if (endpoints != null) {
                ((ArrayNode) endpoints.path("Management").path("IpAddresses")).add(eni.getPrivateIpAddress());
                ((ArrayNode) endpoints.path("Intercluster").path("IpAddresses")).add(eni.getPrivateIpAddress());
            }
            if (record.has("WindowsConfiguration") && !record.path("WindowsConfiguration").has("PreferredFileServerIp")
                    && (!record.path("WindowsConfiguration").has("PreferredSubnetId")
                    || subnetId.equals(record.path("WindowsConfiguration").path("PreferredSubnetId").asText()))) {
                record.withObject("/WindowsConfiguration").put("PreferredFileServerIp", eni.getPrivateIpAddress());
            }
            if (record.has("OpenZFSConfiguration") && !record.path("OpenZFSConfiguration").has("EndpointIpAddress")) {
                record.withObject("/OpenZFSConfiguration").put("EndpointIpAddress", eni.getPrivateIpAddress());
            }
        }
    }

    void releaseInterfaces(String region, ObjectNode record) {
        for (JsonNode eni : record.path("NetworkInterfaceIds")) {
            try {
                ec2.deleteNetworkInterface(region, eni.asText());
            } catch (AwsException e) {
                LOG.debugv("Could not delete {0}: {1}", eni.asText(), e.getMessage());
            }
        }
    }

    // ---------------------------------------------------------------- backups

    public synchronized ObjectNode createBackup(JsonNode input, String region) {
        Optional<ObjectNode> repeat = byToken(region, BACKUP, input);
        if (repeat.isPresent()) {
            return wrap("Backup", repeat.get());
        }
        String fileSystemId = optional(input, "FileSystemId");
        String volumeId = optional(input, "VolumeId");
        if ((fileSystemId == null) == (volumeId == null)) {
            throw badRequest("Give exactly one of FileSystemId and VolumeId.");
        }
        ObjectNode volume = null;
        ObjectNode fileSystem;
        if (volumeId != null) {
            volume = store.get(region, VOLUME, volumeId).orElseThrow(() -> notFound("VolumeNotFound",
                    "Volume " + volumeId + " does not exist."));
            fileSystem = fileSystem(region, volume.path("FileSystemId").asText());
        } else {
            fileSystem = fileSystem(region, fileSystemId);
        }
        if (isScratch(fileSystem)) {
            throw badRequest("Backups are not supported on scratch file systems.");
        }
        if ("ONTAP".equals(fileSystem.path("FileSystemType").asText()) && volume == null) {
            throw badRequest("ONTAP backups are taken of volumes; give a VolumeId.");
        }
        if (!"AVAILABLE".equals(fileSystem.path("Lifecycle").asText())) {
            throw badRequest("File system " + fileSystem.path("FileSystemId").asText() + " is "
                    + fileSystem.path("Lifecycle").asText() + ".");
        }
        String scope = volume != null ? volume.path("VolumeId").asText() : fileSystem.path("FileSystemId").asText();
        for (ObjectNode backup : store.list(region, BACKUP, b -> "CREATING".equals(b.path("Lifecycle").asText()))) {
            if (scope.equals(backup.path("VolumeId").asText(backup.path("FileSystem").path("FileSystemId").asText()))) {
                throw notFound("BackupInProgress", "Another backup of " + scope + " is in progress.");
            }
        }
        String id = backupOf(region, fileSystem, volume, "USER_INITIATED", input.get("Tags"));
        ObjectNode backup = store.get(region, BACKUP, id).orElseThrow();
        if (input.hasNonNull("ClientRequestToken")) {
            meta(backup).put("token", input.get("ClientRequestToken").asText());
            store.put(region, BACKUP, id, backup);
        }
        return wrap("Backup", backup);
    }

    /** Takes a backup of a file system, or of one of its volumes; returns its id. */
    String backupOf(String region, ObjectNode fileSystem, ObjectNode volume, String type, JsonNode tags) {
        String id = store.id("backup");
        ObjectNode backup = store.record("BackupId", id);
        backup.put("Lifecycle", "CREATING").put("Type", type).put("ProgressPercent", 0).put("CreationTime", now());
        if (fileSystem.hasNonNull("KmsKeyId")) {
            backup.put("KmsKeyId", fileSystem.path("KmsKeyId").asText());
        }
        backup.put("ResourceARN", store.arn(region, "backup/" + id));
        ObjectNode copy = view(fileSystem);
        copy.remove("Tags");
        backup.set("FileSystem", copy);
        backup.put("OwnerId", store.account()).put("ResourceType", volume == null ? "FILE_SYSTEM" : "VOLUME");
        if (volume != null) {
            backup.put("VolumeId", volume.path("VolumeId").asText());
            backup.set("Volume", view(volume));
        }
        boolean copyTags = fileSystem.path(configField(fileSystem.path("FileSystemType").asText()))
                .path("CopyTagsToBackups").asBoolean(false);
        backup.set("Tags", store.tags(tags != null && !tags.isEmpty() ? tags
                : copyTags ? fileSystem.path("Tags") : null));
        store.transition(region, BACKUP, backup, "Lifecycle", "CREATING", "AVAILABLE");
        return id;
    }

    public synchronized ObjectNode copyBackup(JsonNode input, String region) {
        String sourceId = text(input, "SourceBackupId");
        String sourceRegion = input.path("SourceRegion").asText(region);
        ObjectNode source = store.get(sourceRegion, BACKUP, sourceId).orElseThrow(() -> notFound("BackupNotFound",
                "Backup " + sourceId + " does not exist."));
        if (!"AVAILABLE".equals(source.path("Lifecycle").asText())) {
            throw notFound("SourceBackupUnavailable", "Backup " + sourceId + " is not AVAILABLE.");
        }
        String id = store.id("backup");
        ObjectNode copy = source.deepCopy();
        copy.put("BackupId", id).put("Lifecycle", "COPYING").put("CreationTime", now())
                .put("ResourceARN", store.arn(region, "backup/" + id)).put("SourceBackupId", sourceId)
                .put("SourceBackupRegion", sourceRegion).put("Type", "USER_INITIATED");
        copy.putObject("_").put("seq", System.nanoTime()).put("idField", "BackupId");
        String kmsKey = optional(input, "KmsKeyId");
        if (kmsKey != null && kms.isResolvable()) {
            try {
                copy.put("KmsKeyId", kms.get().describeKey(kmsKey, region).getArn());
            } catch (AwsException e) {
                throw new AwsException("InvalidDestinationKmsKey", "The KMS key " + kmsKey + " is not valid.", 400);
            }
        }
        copy.set("Tags", input.path("CopyTags").asBoolean(false) ? source.path("Tags").deepCopy()
                : store.tags(input.get("Tags")));
        store.transition(region, BACKUP, copy, "Lifecycle", "COPYING", "AVAILABLE");
        return wrap("Backup", copy);
    }

    public synchronized ObjectNode deleteBackup(JsonNode input, String region) {
        String id = text(input, "BackupId");
        ObjectNode backup = store.get(region, BACKUP, id).orElseThrow(() -> notFound("BackupNotFound",
                "Backup " + id + " does not exist."));
        if ("CREATING".equals(backup.path("Lifecycle").asText()) || "COPYING".equals(backup.path("Lifecycle")
                .asText())) {
            throw notFound("BackupInProgress", "Backup " + id + " is still being created.");
        }
        for (ObjectNode copy : store.list(region, BACKUP, b -> id.equals(b.path("SourceBackupId").asText())
                && "COPYING".equals(b.path("Lifecycle").asText()))) {
            throw notFound("BackupBeingCopied", "Backup " + id + " is being copied to "
                    + copy.path("BackupId").asText() + ".");
        }
        store.delete(region, BACKUP, id);
        ObjectNode response = store.mapper().createObjectNode();
        response.put("BackupId", id).put("Lifecycle", "DELETED");
        return response;
    }

    public synchronized ObjectNode describeBackups(JsonNode input, String region) {
        List<String> ids = strings(input.path("BackupIds"));
        List<ObjectNode> items = new ArrayList<>();
        if (!ids.isEmpty()) {
            for (String id : ids) {
                items.add(store.get(region, BACKUP, id).orElseThrow(() -> notFound("BackupNotFound",
                        "Backup " + id + " does not exist.")));
            }
        } else {
            for (ObjectNode backup : store.list(region, BACKUP, b -> true)) {
                if (FsxStore.filtered(input.path("Filters"), Map.of(
                        "file-system-id", backup.path("FileSystem").path("FileSystemId").asText(),
                        "backup-type", backup.path("Type").asText(),
                        "file-system-type", backup.path("FileSystem").path("FileSystemType").asText(),
                        "volume-id", backup.path("VolumeId").asText(""),
                        "data-repository-type", "S3",
                        "file-cache-id", "", "file-cache-type", ""))) {
                    items.add(backup);
                }
            }
        }
        return store.page(items, input, "Backups");
    }

    // ---------------------------------------------------------------- file caches

    public synchronized ObjectNode createFileCache(JsonNode input, String region) {
        Optional<ObjectNode> repeat = byToken(region, CACHE, input);
        if (repeat.isPresent()) {
            return wrap("FileCache", repeat.get());
        }
        int capacity = input.path("StorageCapacity").asInt();
        require(capacity == 1200 || (capacity > 0 && capacity % 2400 == 0),
                "A file cache's storage capacity must be 1200 or a multiple of 2400 GiB.");
        JsonNode lustre = input.path("LustreConfiguration");
        require(lustre.path("PerUnitStorageThroughput").asInt() == 1000,
                "A file cache's PerUnitStorageThroughput must be 1000.");
        require("CACHE_1".equals(lustre.path("DeploymentType").asText()), "A file cache's DeploymentType must be "
                + "CACHE_1.");
        JsonNode associations = input.path("DataRepositoryAssociations");
        if (associations.size() > 8) {
            throw badRequest("A file cache has at most 8 data repository associations.");
        }
        String id = store.id("fc");
        ObjectNode cache = store.record("FileCacheId", id);
        cache.put("OwnerId", store.account()).put("CreationTime", now()).put("FileCacheType", "LUSTRE")
                .put("FileCacheTypeVersion", text(input, "FileCacheTypeVersion")).put("Lifecycle", "CREATING")
                .put("StorageCapacity", capacity);
        place(region, cache, strings(input.path("SubnetIds")), strings(input.path("SecurityGroupIds")),
                "File Cache " + id);
        cache.put("DNSName", id + ".fsx." + region + "." + AwsRegions.dnsSuffixFor(region));
        String kmsKey = kmsKey(input, region, true);
        if (kmsKey != null) {
            cache.put("KmsKeyId", kmsKey);
        }
        cache.put("ResourceARN", store.arn(region, "file-cache/" + id));
        ObjectNode out = cache.putObject("LustreConfiguration");
        out.put("PerUnitStorageThroughput", 1000).put("DeploymentType", "CACHE_1")
                .put("MountName", store.random(LOWER, 8))
                .put("WeeklyMaintenanceStartTime", lustre.path("WeeklyMaintenanceStartTime").asText("1:05:00"));
        out.set("MetadataConfiguration", lustre.path("MetadataConfiguration").deepCopy());
        out.putObject("LogConfiguration").put("Level", "DISABLED");
        cache.set("Tags", store.tags(input.get("Tags")));
        ArrayNode associationIds = cache.putArray("DataRepositoryAssociationIds");
        meta(cache).set("associations", associations.deepCopy());
        meta(cache).put("copyTags", input.path("CopyTagsToDataRepositoryAssociations").asBoolean(false));
        if (input.hasNonNull("ClientRequestToken")) {
            meta(cache).put("token", input.get("ClientRequestToken").asText());
        }
        store.put(region, CACHE, id, cache);
        if (associationIds.isEmpty() && repositories.isResolvable()) {
            for (JsonNode association : associations) {
                associationIds.add(repositories.get().cacheAssociation(region, cache, association));
            }
        }
        store.transition(region, CACHE, cache, "Lifecycle", "CREATING", "AVAILABLE");
        return wrap("FileCache", cache);
    }

    public synchronized ObjectNode describeFileCaches(JsonNode input, String region) {
        List<String> ids = strings(input.path("FileCacheIds"));
        List<ObjectNode> items = new ArrayList<>();
        for (String id : ids) {
            items.add(fileCache(region, id));
        }
        if (ids.isEmpty()) {
            items.addAll(store.list(region, CACHE, cache -> true));
        }
        return store.page(items, input, "FileCaches");
    }

    public synchronized ObjectNode updateFileCache(JsonNode input, String region) {
        ObjectNode cache = fileCache(region, text(input, "FileCacheId"));
        JsonNode lustre = input.path("LustreConfiguration");
        if (lustre.hasNonNull("WeeklyMaintenanceStartTime")) {
            cache.withObject("/LustreConfiguration").put("WeeklyMaintenanceStartTime",
                    lustre.get("WeeklyMaintenanceStartTime").asText());
        }
        store.transition(region, CACHE, cache, "Lifecycle", "UPDATING", "AVAILABLE");
        return wrap("FileCache", cache);
    }

    public synchronized ObjectNode deleteFileCache(JsonNode input, String region) {
        ObjectNode cache = fileCache(region, text(input, "FileCacheId"));
        releaseInterfaces(region, cache);
        if (repositories.isResolvable()) {
            repositories.get().dropAssociations(region, cache.path("FileCacheId").asText());
        }
        store.transition(region, CACHE, cache, "Lifecycle", "DELETING", null);
        ObjectNode response = store.mapper().createObjectNode();
        response.put("FileCacheId", cache.path("FileCacheId").asText()).put("Lifecycle", "DELETING");
        return response;
    }

    ObjectNode fileCache(String region, String id) {
        return store.get(region, CACHE, id).orElseThrow(() -> notFound("FileCacheNotFound",
                "File cache " + id + " does not exist."));
    }

    // ---------------------------------------------------------------- aliases

    public synchronized ObjectNode associateFileSystemAliases(JsonNode input, String region) {
        ObjectNode fileSystem = windowsFileSystem(region, text(input, "FileSystemId"));
        ArrayNode aliases = fileSystem.withObject("/WindowsConfiguration").withArray("/Aliases");
        ObjectNode response = store.mapper().createObjectNode();
        ArrayNode out = response.putArray("Aliases");
        for (String name : strings(input.path("Aliases"))) {
            String alias = name.toLowerCase(Locale.ROOT);
            if (!alias.matches("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+")) {
                throw badRequest("Alias " + name + " is not a fully qualified domain name.");
            }
            boolean present = false;
            for (JsonNode existing : aliases) {
                present |= alias.equals(existing.path("Name").asText());
            }
            if (!present) {
                aliases.addObject().put("Name", alias).put("Lifecycle", "AVAILABLE");
            }
            out.addObject().put("Name", alias).put("Lifecycle", "CREATING");
        }
        if (aliases.size() > 50) {
            throw notFound("ServiceLimitExceeded", "A file system has at most 50 aliases.");
        }
        store.put(region, FILE_SYSTEM, fileSystem.path("FileSystemId").asText(), fileSystem);
        return response;
    }

    public synchronized ObjectNode disassociateFileSystemAliases(JsonNode input, String region) {
        ObjectNode fileSystem = windowsFileSystem(region, text(input, "FileSystemId"));
        ArrayNode aliases = fileSystem.withObject("/WindowsConfiguration").withArray("/Aliases");
        ObjectNode response = store.mapper().createObjectNode();
        ArrayNode out = response.putArray("Aliases");
        for (String name : strings(input.path("Aliases"))) {
            String alias = name.toLowerCase(Locale.ROOT);
            for (int index = aliases.size() - 1; index >= 0; index--) {
                if (alias.equals(aliases.get(index).path("Name").asText())) {
                    aliases.remove(index);
                }
            }
            out.addObject().put("Name", alias).put("Lifecycle", "DELETING");
        }
        store.put(region, FILE_SYSTEM, fileSystem.path("FileSystemId").asText(), fileSystem);
        return response;
    }

    public synchronized ObjectNode describeFileSystemAliases(JsonNode input, String region) {
        ObjectNode fileSystem = windowsFileSystem(region, text(input, "FileSystemId"));
        List<ObjectNode> items = new ArrayList<>();
        fileSystem.path("WindowsConfiguration").path("Aliases").forEach(alias -> items.add(((ObjectNode) alias)
                .deepCopy()));
        for (ObjectNode item : items) {
            item.putObject("_");
        }
        return store.page(items, input, "Aliases");
    }

    private ObjectNode windowsFileSystem(String region, String id) {
        ObjectNode fileSystem = fileSystem(region, id);
        if (!"WINDOWS".equals(fileSystem.path("FileSystemType").asText())) {
            throw badRequest("DNS aliases are for FSx for Windows File Server file systems.");
        }
        return fileSystem;
    }

    // ---------------------------------------------------------------- shared VPC

    public synchronized ObjectNode describeSharedVpcConfiguration(JsonNode input, String region) {
        ObjectNode response = store.mapper().createObjectNode();
        response.put("EnableFsxRouteTableUpdatesFromParticipantAccounts", store.get(region, "account", "shared-vpc")
                .map(setting -> setting.path("enabled").asText()).orElse("false"));
        return response;
    }

    public synchronized ObjectNode updateSharedVpcConfiguration(JsonNode input, String region) {
        String value = input.path("EnableFsxRouteTableUpdatesFromParticipantAccounts").asText("false");
        if (!Set.of("true", "false").contains(value)) {
            throw badRequest("EnableFsxRouteTableUpdatesFromParticipantAccounts must be true or false.");
        }
        ObjectNode setting = store.record("id", "shared-vpc");
        setting.put("enabled", value);
        store.put(region, "account", "shared-vpc", setting);
        ObjectNode response = store.mapper().createObjectNode();
        response.put("EnableFsxRouteTableUpdatesFromParticipantAccounts", value);
        return response;
    }

    // ---------------------------------------------------------------- shared

    private Optional<ObjectNode> byToken(String region, String kind, JsonNode input) {
        String token = optional(input, "ClientRequestToken");
        if (token == null) {
            return Optional.empty();
        }
        return store.list(region, kind, record -> token.equals(meta(record).path("token").asText(null))).stream()
                .findFirst();
    }

    private ObjectNode wrap(String field, ObjectNode record) {
        ObjectNode response = store.mapper().createObjectNode();
        response.set(field, view(record));
        return response;
    }
}
