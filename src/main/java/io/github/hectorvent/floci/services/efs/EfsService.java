package io.github.hectorvent.floci.services.efs;

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
import io.github.hectorvent.floci.services.ec2.model.NetworkInterface;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import io.github.hectorvent.floci.services.efs.model.EfsAccessPoint;
import io.github.hectorvent.floci.services.efs.model.FileSystem;
import io.github.hectorvent.floci.services.efs.model.MountTarget;
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
 * Amazon EFS restJson1 — file systems, mount targets, and access points.
 *
 * <p>Resources become {@code available} immediately so Alchemy wait-loops do not stall.
 * Mount targets create a requester-managed ENI so security-group deletes observe a
 * released network interface.
 */
@ApplicationScoped
public class EfsService implements Resettable {

    static final String SERVICE = "elasticfilesystem";
    private static final int MAX_SECURITY_GROUPS = 5;
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final StorageBackend<String, FileSystem> fileSystems;
    private final StorageBackend<String, MountTarget> mountTargets;
    private final StorageBackend<String, EfsAccessPoint> accessPoints;
    private final Ec2Service ec2Service;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public EfsService(StorageFactory factory, Ec2Service ec2Service, RegionResolver regionResolver,
                      ObjectMapper objectMapper) {
        this.fileSystems = factory.create("efs", "efs-file-systems.json",
                new TypeReference<Map<String, FileSystem>>() {
                });
        this.mountTargets = factory.create("efs", "efs-mount-targets.json",
                new TypeReference<Map<String, MountTarget>>() {
                });
        this.accessPoints = factory.create("efs", "efs-access-points.json",
                new TypeReference<Map<String, EfsAccessPoint>>() {
                });
        this.ec2Service = ec2Service;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    EfsService(StorageBackend<String, FileSystem> fileSystems,
               StorageBackend<String, MountTarget> mountTargets,
               StorageBackend<String, EfsAccessPoint> accessPoints,
               Ec2Service ec2Service, RegionResolver regionResolver, ObjectMapper objectMapper) {
        this.fileSystems = fileSystems;
        this.mountTargets = mountTargets;
        this.accessPoints = accessPoints;
        this.ec2Service = ec2Service;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @Override
    public void clear() {
        fileSystems.clear();
        mountTargets.clear();
        accessPoints.clear();
    }

    public synchronized ObjectNode createFileSystem(String region, JsonNode request) {
        String token = requireText(request, "CreationToken");
        FileSystem existing = findFileSystemByToken(region, token);
        if (existing != null) {
            throw efsError("FileSystemAlreadyExists",
                    "File system '" + existing.getFileSystemId() + "' already exists.",
                    409, Map.of("ErrorCode", "FileSystemAlreadyExists",
                            "FileSystemId", existing.getFileSystemId()));
        }

        String fileSystemId = "fs-" + randomHex(8);
        FileSystem fs = new FileSystem();
        fs.setFileSystemId(fileSystemId);
        fs.setOwnerId(regionResolver.getAccountId());
        fs.setCreationToken(token);
        fs.setCreationTime(Instant.now().getEpochSecond());
        fs.setLifeCycleState("available");
        fs.setPerformanceMode(textOr(request, "PerformanceMode", "generalPurpose"));
        fs.setThroughputMode(textOr(request, "ThroughputMode", "bursting"));
        if (request.hasNonNull("ProvisionedThroughputInMibps")) {
            fs.setProvisionedThroughputInMibps(request.get("ProvisionedThroughputInMibps").asDouble());
        }
        boolean encrypted = request.path("Encrypted").asBoolean(false);
        fs.setEncrypted(encrypted);
        if (request.hasNonNull("KmsKeyId")) {
            fs.setKmsKeyId(request.get("KmsKeyId").asText());
        } else if (encrypted) {
            fs.setKmsKeyId("alias/aws/elasticfilesystem");
        }
        if (request.hasNonNull("AvailabilityZoneName")) {
            fs.setAvailabilityZoneName(request.get("AvailabilityZoneName").asText());
        }
        if (request.has("Backup")) {
            fs.setBackupPolicyStatus(request.path("Backup").asBoolean(false) ? "ENABLED" : "DISABLED");
        }
        fs.setRegion(region);
        fs.setTags(readTags(request.get("Tags")));
        fs.setFileSystemArn(regionResolver.buildArn(SERVICE, region, "file-system/" + fileSystemId));
        fileSystems.put(storageKey(region, fileSystemId), fs);
        return toFileSystemNode(fs);
    }

    public ObjectNode describeFileSystems(String region, String fileSystemId, String creationToken) {
        List<FileSystem> matches = new ArrayList<>();
        if (fileSystemId != null && !fileSystemId.isBlank()) {
            matches.add(requireFileSystem(region, fileSystemId));
        } else if (creationToken != null && !creationToken.isBlank()) {
            FileSystem found = findFileSystemByToken(region, creationToken);
            if (found != null) {
                matches.add(found);
            }
        } else {
            for (FileSystem fs : fileSystems.values()) {
                if (region.equals(fs.getRegion()) && !"deleted".equals(fs.getLifeCycleState())) {
                    matches.add(fs);
                }
            }
        }
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("FileSystems");
        for (FileSystem fs : matches) {
            list.add(toFileSystemNode(fs));
        }
        return response;
    }

    public synchronized void deleteFileSystem(String region, String fileSystemId) {
        FileSystem fs = requireFileSystem(region, fileSystemId);
        if (!mountTargetsFor(fs.getFileSystemId()).isEmpty()) {
            throw efsError("FileSystemInUse",
                    "File system '" + fileSystemId + "' has mount targets.", 409);
        }
        fileSystems.delete(storageKey(region, fs.getFileSystemId()));
    }

    public ObjectNode describeLifecycleConfiguration(String region, String fileSystemId) {
        FileSystem fs = requireFileSystem(region, fileSystemId);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode policies = response.putArray("LifecyclePolicies");
        for (Map<String, String> policy : fs.getLifecyclePolicies()) {
            ObjectNode node = objectMapper.createObjectNode();
            policy.forEach(node::put);
            policies.add(node);
        }
        return response;
    }

    public synchronized ObjectNode putLifecycleConfiguration(String region, String fileSystemId, JsonNode request) {
        FileSystem fs = requireFileSystem(region, fileSystemId);
        List<Map<String, String>> policies = new ArrayList<>();
        for (JsonNode item : request.path("LifecyclePolicies")) {
            Map<String, String> policy = new LinkedHashMap<>();
            item.fields().forEachRemaining(entry -> {
                if (!entry.getValue().isNull()) {
                    policy.put(entry.getKey(), entry.getValue().asText());
                }
            });
            if (!policy.isEmpty()) {
                policies.add(policy);
            }
        }
        fs.setLifecyclePolicies(policies);
        fileSystems.put(storageKey(region, fs.getFileSystemId()), fs);
        return describeLifecycleConfiguration(region, fileSystemId);
    }

    public ObjectNode describeFileSystemPolicy(String region, String fileSystemId) {
        FileSystem fs = requireFileSystem(region, fileSystemId);
        if (fs.getPolicy() == null || fs.getPolicy().isBlank()) {
            throw efsError("PolicyNotFound",
                    "File system '" + fileSystemId + "' does not have a policy.", 404);
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.put("FileSystemId", fs.getFileSystemId());
        response.put("Policy", fs.getPolicy());
        return response;
    }

    public synchronized ObjectNode putFileSystemPolicy(String region, String fileSystemId, JsonNode request) {
        FileSystem fs = requireFileSystem(region, fileSystemId);
        fs.setPolicy(requireText(request, "Policy"));
        fileSystems.put(storageKey(region, fs.getFileSystemId()), fs);
        return describeFileSystemPolicy(region, fileSystemId);
    }

    public synchronized void deleteFileSystemPolicy(String region, String fileSystemId) {
        FileSystem fs = requireFileSystem(region, fileSystemId);
        fs.setPolicy(null);
        fileSystems.put(storageKey(region, fs.getFileSystemId()), fs);
    }

    public ObjectNode describeBackupPolicy(String region, String fileSystemId) {
        FileSystem fs = requireFileSystem(region, fileSystemId);
        ObjectNode response = objectMapper.createObjectNode();
        response.putObject("BackupPolicy").put("Status",
                fs.getBackupPolicyStatus() != null ? fs.getBackupPolicyStatus() : "DISABLED");
        return response;
    }

    public synchronized ObjectNode putBackupPolicy(String region, String fileSystemId, JsonNode request) {
        FileSystem fs = requireFileSystem(region, fileSystemId);
        fs.setBackupPolicyStatus(requireText(request.path("BackupPolicy"), "Status"));
        fileSystems.put(storageKey(region, fs.getFileSystemId()), fs);
        ObjectNode response = objectMapper.createObjectNode();
        response.putObject("BackupPolicy").put("Status", fs.getBackupPolicyStatus());
        return response;
    }

    public synchronized ObjectNode updateFileSystem(String region, String fileSystemId, JsonNode request) {
        FileSystem fs = requireFileSystem(region, fileSystemId);
        if (request.hasNonNull("ThroughputMode")) {
            fs.setThroughputMode(request.get("ThroughputMode").asText());
        }
        if (request.hasNonNull("ProvisionedThroughputInMibps")) {
            fs.setProvisionedThroughputInMibps(request.get("ProvisionedThroughputInMibps").asDouble());
        } else if ("bursting".equals(fs.getThroughputMode()) || "elastic".equals(fs.getThroughputMode())) {
            fs.setProvisionedThroughputInMibps(null);
        }
        fileSystems.put(storageKey(region, fs.getFileSystemId()), fs);
        return toFileSystemNode(fs);
    }

    public synchronized ObjectNode updateFileSystemProtection(String region, String fileSystemId, JsonNode request) {
        FileSystem fs = requireFileSystem(region, fileSystemId);
        if (request.hasNonNull("ReplicationOverwriteProtection")) {
            fs.setReplicationOverwriteProtection(request.get("ReplicationOverwriteProtection").asText());
        }
        fileSystems.put(storageKey(region, fs.getFileSystemId()), fs);
        ObjectNode response = objectMapper.createObjectNode();
        response.putObject("FileSystemProtection")
                .put("ReplicationOverwriteProtection", fs.getReplicationOverwriteProtection());
        return response;
    }

    public ObjectNode describeReplicationConfigurations(String region, String fileSystemId) {
        if (fileSystemId != null && !fileSystemId.isBlank()) {
            requireFileSystem(region, fileSystemId);
        }
        throw efsError("ReplicationNotFound", "No replication configuration exists.", 404);
    }

    public synchronized ObjectNode tagResource(String region, String resourceId, JsonNode request) {
        Map<String, String> incoming = readTags(request.get("Tags"));
        if (resourceId.startsWith("fsap-")) {
            EfsAccessPoint accessPoint = requireAccessPoint(region, resourceId);
            accessPoint.getTags().putAll(incoming);
            accessPoints.put(storageKey(region, accessPoint.getAccessPointId()), accessPoint);
        } else {
            FileSystem fs = requireFileSystem(region, resourceId);
            fs.getTags().putAll(incoming);
            fileSystems.put(storageKey(region, fs.getFileSystemId()), fs);
        }
        return objectMapper.createObjectNode();
    }

    public synchronized ObjectNode untagResource(String region, String resourceId, List<String> tagKeys) {
        Map<String, String> tags = resourceId.startsWith("fsap-")
                ? requireAccessPoint(region, resourceId).getTags()
                : requireFileSystem(region, resourceId).getTags();
        if (tagKeys != null) {
            for (String key : tagKeys) {
                tags.remove(key);
            }
        }
        if (resourceId.startsWith("fsap-")) {
            EfsAccessPoint accessPoint = requireAccessPoint(region, resourceId);
            accessPoints.put(storageKey(region, accessPoint.getAccessPointId()), accessPoint);
        } else {
            FileSystem fs = requireFileSystem(region, resourceId);
            fileSystems.put(storageKey(region, fs.getFileSystemId()), fs);
        }
        return objectMapper.createObjectNode();
    }

    public ObjectNode listTagsForResource(String region, String resourceId) {
        Map<String, String> tags = resourceId.startsWith("fsap-")
                ? requireAccessPoint(region, resourceId).getTags()
                : requireFileSystem(region, resourceId).getTags();
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Tags", tagsArray(tags));
        return response;
    }

    public ObjectNode describeTags(String region, String fileSystemId) {
        return listTagsForResource(region, fileSystemId);
    }

    public synchronized void createTags(String region, String fileSystemId, JsonNode request) {
        tagResource(region, fileSystemId, request);
    }

    public synchronized void deleteTags(String region, String fileSystemId, JsonNode request) {
        List<String> keys = new ArrayList<>();
        for (JsonNode item : request.path("TagKeys")) {
            keys.add(item.asText());
        }
        untagResource(region, fileSystemId, keys);
    }

    public synchronized ObjectNode createMountTarget(String region, JsonNode request) {
        String fileSystemId = requireText(request, "FileSystemId");
        String subnetId = requireText(request, "SubnetId");
        FileSystem fs = requireFileSystem(region, fileSystemId);
        if (!"available".equals(fs.getLifeCycleState())) {
            throw efsError("IncorrectFileSystemLifeCycleState",
                    "File system '" + fileSystemId + "' is not available.", 409);
        }

        Subnet subnet = resolveSubnet(region, subnetId);
        if (fs.getAvailabilityZoneName() != null
                && !fs.getAvailabilityZoneName().equals(subnet.getAvailabilityZone())) {
            throw efsError("AvailabilityZonesMismatch",
                    "Mount target availability zone must match the One Zone file system.", 400);
        }

        for (MountTarget existing : mountTargetsFor(fileSystemId)) {
            if (!subnet.getVpcId().equals(existing.getVpcId())) {
                throw efsError("MountTargetConflict",
                        "Mount targets for a file system must be in the same VPC.", 409);
            }
            if (subnetId.equals(existing.getSubnetId())
                    || subnet.getAvailabilityZone().equals(existing.getAvailabilityZoneName())) {
                throw efsError("MountTargetConflict",
                        "A mount target already exists in the specified subnet or availability zone.", 409);
            }
        }

        List<String> securityGroups = readSecurityGroups(request.get("SecurityGroups"));
        if (securityGroups.isEmpty()) {
            securityGroups = List.of(defaultSecurityGroupId(region, subnet.getVpcId()));
        }
        if (securityGroups.size() > MAX_SECURITY_GROUPS) {
            throw efsError("SecurityGroupLimitExceeded",
                    "The maximum number of security groups per mount target is 5.", 400);
        }

        String mountTargetId = "fsmt-" + randomHex(17);
        String ipAddress = textOrNull(request, "IpAddress");
        NetworkInterface eni;
        try {
            eni = ec2Service.createNetworkInterface(
                    region,
                    subnetId,
                    "Mount target " + mountTargetId + " for file system " + fileSystemId,
                    ipAddress,
                    securityGroups,
                    "efs",
                    List.of());
        } catch (AwsException e) {
            throw translateEc2(e, subnetId);
        }

        MountTarget target = new MountTarget();
        target.setMountTargetId(mountTargetId);
        target.setFileSystemId(fileSystemId);
        target.setSubnetId(subnetId);
        target.setVpcId(subnet.getVpcId());
        target.setOwnerId(regionResolver.getAccountId());
        target.setLifeCycleState("available");
        target.setIpAddress(eni.getPrivateIpAddress());
        target.setNetworkInterfaceId(eni.getNetworkInterfaceId());
        target.setAvailabilityZoneName(subnet.getAvailabilityZone());
        target.setAvailabilityZoneId(subnet.getAvailabilityZoneId());
        target.setSecurityGroups(new ArrayList<>(securityGroups));
        mountTargets.put(storageKey(region, mountTargetId), target);
        return toMountTargetNode(target);
    }

    public ObjectNode describeMountTargets(String region, String fileSystemId, String mountTargetId,
                                           String accessPointId) {
        List<MountTarget> matches = new ArrayList<>();
        if (mountTargetId != null && !mountTargetId.isBlank()) {
            matches.add(requireMountTarget(region, mountTargetId));
        } else if (accessPointId != null && !accessPointId.isBlank()) {
            EfsAccessPoint accessPoint = requireAccessPoint(region, accessPointId);
            matches.addAll(mountTargetsFor(accessPoint.getFileSystemId()));
        } else if (fileSystemId != null && !fileSystemId.isBlank()) {
            requireFileSystem(region, fileSystemId);
            matches.addAll(mountTargetsFor(fileSystemId));
        } else {
            throw efsError("BadRequest", "FileSystemId, MountTargetId, or AccessPointId must be specified.", 400);
        }
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("MountTargets");
        for (MountTarget target : matches) {
            list.add(toMountTargetNode(target));
        }
        return response;
    }

    public ObjectNode describeMountTargetSecurityGroups(String region, String mountTargetId) {
        MountTarget target = requireMountTarget(region, mountTargetId);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode groups = response.putArray("SecurityGroups");
        target.getSecurityGroups().forEach(groups::add);
        return response;
    }

    public synchronized ObjectNode modifyMountTargetSecurityGroups(String region, String mountTargetId,
                                                                   JsonNode request) {
        MountTarget target = requireMountTarget(region, mountTargetId);
        List<String> securityGroups = readSecurityGroups(request.get("SecurityGroups"));
        if (securityGroups.isEmpty()) {
            throw efsError("BadRequest", "SecurityGroups must contain at least one security group.", 400);
        }
        if (securityGroups.size() > MAX_SECURITY_GROUPS) {
            throw efsError("SecurityGroupLimitExceeded",
                    "The maximum number of security groups per mount target is 5.", 400);
        }
        try {
            ec2Service.modifyNetworkInterfaceAttribute(
                    region, target.getNetworkInterfaceId(), null, null, securityGroups);
        } catch (AwsException e) {
            throw translateEc2(e, target.getSubnetId());
        }
        target.setSecurityGroups(new ArrayList<>(securityGroups));
        mountTargets.put(storageKey(region, target.getMountTargetId()), target);
        return objectMapper.createObjectNode();
    }

    public synchronized void deleteMountTarget(String region, String mountTargetId) {
        MountTarget target = requireMountTarget(region, mountTargetId);
        if (target.getNetworkInterfaceId() != null) {
            try {
                ec2Service.deleteNetworkInterface(region, target.getNetworkInterfaceId());
            } catch (AwsException e) {
                if (!"InvalidNetworkInterfaceID.NotFound".equals(e.getErrorCode())) {
                    throw e;
                }
            }
        }
        mountTargets.delete(storageKey(region, target.getMountTargetId()));
    }

    public synchronized ObjectNode createAccessPoint(String region, JsonNode request) {
        String clientToken = requireText(request, "ClientToken");
        String fileSystemId = requireText(request, "FileSystemId");
        requireFileSystem(region, fileSystemId);
        EfsAccessPoint existing = findAccessPointByToken(region, clientToken);
        if (existing != null) {
            throw efsError("AccessPointAlreadyExists",
                    "Access point '" + existing.getAccessPointId() + "' already exists.",
                    409, Map.of("ErrorCode", "AccessPointAlreadyExists",
                            "AccessPointId", existing.getAccessPointId()));
        }

        String accessPointId = "fsap-" + randomHex(17);
        EfsAccessPoint accessPoint = new EfsAccessPoint();
        accessPoint.setAccessPointId(accessPointId);
        accessPoint.setAccessPointArn(regionResolver.buildArn(SERVICE, region, "access-point/" + accessPointId));
        accessPoint.setFileSystemId(fileSystemId);
        accessPoint.setClientToken(clientToken);
        accessPoint.setOwnerId(regionResolver.getAccountId());
        accessPoint.setLifeCycleState("available");
        accessPoint.setRegion(region);
        accessPoint.setTags(readTags(request.get("Tags")));
        if (request.hasNonNull("PosixUser")) {
            accessPoint.setPosixUser(objectMapper.convertValue(request.get("PosixUser"), MAP));
        }
        if (request.hasNonNull("RootDirectory")) {
            accessPoint.setRootDirectory(objectMapper.convertValue(request.get("RootDirectory"), MAP));
        }
        accessPoints.put(storageKey(region, accessPointId), accessPoint);
        return toAccessPointNode(accessPoint);
    }

    public ObjectNode describeAccessPoints(String region, String accessPointId, String fileSystemId) {
        List<EfsAccessPoint> matches = new ArrayList<>();
        if (accessPointId != null && !accessPointId.isBlank()) {
            matches.add(requireAccessPoint(region, accessPointId));
        } else if (fileSystemId != null && !fileSystemId.isBlank()) {
            requireFileSystem(region, fileSystemId);
            for (EfsAccessPoint accessPoint : accessPoints.values()) {
                if (fileSystemId.equals(accessPoint.getFileSystemId())
                        && !"deleted".equals(accessPoint.getLifeCycleState())) {
                    matches.add(accessPoint);
                }
            }
        } else {
            for (EfsAccessPoint accessPoint : accessPoints.values()) {
                if (region.equals(accessPoint.getRegion())
                        && !"deleted".equals(accessPoint.getLifeCycleState())) {
                    matches.add(accessPoint);
                }
            }
        }
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("AccessPoints");
        for (EfsAccessPoint accessPoint : matches) {
            list.add(toAccessPointNode(accessPoint));
        }
        return response;
    }

    public synchronized void deleteAccessPoint(String region, String accessPointId) {
        EfsAccessPoint accessPoint = requireAccessPoint(region, accessPointId);
        accessPoints.delete(storageKey(region, accessPoint.getAccessPointId()));
    }

    public static String accessPointIdFromArn(String arn) {
        if (arn == null || arn.isBlank()) {
            return null;
        }
        int idx = arn.indexOf("access-point/");
        if (idx < 0) {
            return null;
        }
        String id = arn.substring(idx + "access-point/".length()).trim();
        return id.isEmpty() ? null : id;
    }

    public static String dockerVolumeName(String fileSystemId) {
        return "floci-efs-" + fileSystemId;
    }

    public Optional<EfsAccessPoint> findAccessPoint(String region, String accessPointId) {
        if (accessPointId == null || accessPointId.isBlank()) {
            return Optional.empty();
        }
        EfsAccessPoint accessPoint = accessPoints.get(storageKey(region, accessPointId)).orElse(null);
        if (accessPoint == null || "deleted".equals(accessPoint.getLifeCycleState())) {
            return Optional.empty();
        }
        return Optional.of(accessPoint);
    }

    public boolean hasMountTarget(String fileSystemId) {
        return !mountTargetsFor(fileSystemId).isEmpty();
    }

    private FileSystem requireFileSystem(String region, String fileSystemId) {
        FileSystem fs = fileSystems.get(storageKey(region, fileSystemId)).orElse(null);
        if (fs == null || "deleted".equals(fs.getLifeCycleState())) {
            throw efsError("FileSystemNotFound",
                    "File system '" + fileSystemId + "' does not exist.", 404);
        }
        return fs;
    }

    private MountTarget requireMountTarget(String region, String mountTargetId) {
        MountTarget target = mountTargets.get(storageKey(region, mountTargetId)).orElse(null);
        if (target == null || "deleted".equals(target.getLifeCycleState())) {
            throw efsError("MountTargetNotFound",
                    "Mount target '" + mountTargetId + "' does not exist.", 404);
        }
        return target;
    }

    private EfsAccessPoint requireAccessPoint(String region, String accessPointId) {
        EfsAccessPoint accessPoint = accessPoints.get(storageKey(region, accessPointId)).orElse(null);
        if (accessPoint == null || "deleted".equals(accessPoint.getLifeCycleState())) {
            throw efsError("AccessPointNotFound",
                    "Access point '" + accessPointId + "' does not exist.", 404);
        }
        return accessPoint;
    }

    private FileSystem findFileSystemByToken(String region, String token) {
        for (FileSystem fs : fileSystems.values()) {
            if (region.equals(fs.getRegion()) && token.equals(fs.getCreationToken())
                    && !"deleted".equals(fs.getLifeCycleState())) {
                return fs;
            }
        }
        return null;
    }

    private EfsAccessPoint findAccessPointByToken(String region, String token) {
        for (EfsAccessPoint accessPoint : accessPoints.values()) {
            if (region.equals(accessPoint.getRegion()) && token.equals(accessPoint.getClientToken())
                    && !"deleted".equals(accessPoint.getLifeCycleState())) {
                return accessPoint;
            }
        }
        return null;
    }

    private List<MountTarget> mountTargetsFor(String fileSystemId) {
        List<MountTarget> matches = new ArrayList<>();
        for (MountTarget target : mountTargets.values()) {
            if (fileSystemId.equals(target.getFileSystemId())
                    && !"deleted".equals(target.getLifeCycleState())) {
                matches.add(target);
            }
        }
        return matches;
    }

    private Subnet resolveSubnet(String region, String subnetId) {
        try {
            return ec2Service.requireSubnet(region, subnetId);
        } catch (AwsException e) {
            throw translateEc2(e, subnetId);
        }
    }

    private String defaultSecurityGroupId(String region, String vpcId) {
        List<SecurityGroup> groups = ec2Service.describeSecurityGroups(
                region, List.of(), List.of("default"), Map.of("vpc-id", List.of(vpcId)));
        if (groups.isEmpty()) {
            throw efsError("SecurityGroupNotFound",
                    "The default security group for VPC '" + vpcId + "' does not exist.", 400);
        }
        return groups.get(0).getGroupId();
    }

    private ObjectNode toFileSystemNode(FileSystem fs) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("OwnerId", fs.getOwnerId());
        node.put("CreationToken", fs.getCreationToken());
        node.put("FileSystemId", fs.getFileSystemId());
        node.put("FileSystemArn", fs.getFileSystemArn());
        node.put("CreationTime", fs.getCreationTime());
        node.put("LifeCycleState", fs.getLifeCycleState());
        String name = fs.getTags().get("Name");
        if (name != null) {
            node.put("Name", name);
        }
        node.put("NumberOfMountTargets", mountTargetsFor(fs.getFileSystemId()).size());
        node.putObject("SizeInBytes").put("Value", 0);
        node.put("PerformanceMode", fs.getPerformanceMode());
        node.put("Encrypted", Boolean.TRUE.equals(fs.getEncrypted()));
        if (fs.getKmsKeyId() != null) {
            node.put("KmsKeyId", fs.getKmsKeyId());
        }
        node.put("ThroughputMode", fs.getThroughputMode());
        if (fs.getProvisionedThroughputInMibps() != null) {
            node.put("ProvisionedThroughputInMibps", fs.getProvisionedThroughputInMibps());
        }
        if (fs.getAvailabilityZoneName() != null) {
            node.put("AvailabilityZoneName", fs.getAvailabilityZoneName());
        }
        if (fs.getAvailabilityZoneId() != null) {
            node.put("AvailabilityZoneId", fs.getAvailabilityZoneId());
        }
        node.set("Tags", tagsArray(fs.getTags()));
        node.putObject("FileSystemProtection")
                .put("ReplicationOverwriteProtection", fs.getReplicationOverwriteProtection());
        return node;
    }

    private ObjectNode toMountTargetNode(MountTarget target) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("OwnerId", target.getOwnerId());
        node.put("MountTargetId", target.getMountTargetId());
        node.put("FileSystemId", target.getFileSystemId());
        node.put("SubnetId", target.getSubnetId());
        node.put("LifeCycleState", target.getLifeCycleState());
        if (target.getIpAddress() != null) {
            node.put("IpAddress", target.getIpAddress());
        }
        if (target.getNetworkInterfaceId() != null) {
            node.put("NetworkInterfaceId", target.getNetworkInterfaceId());
        }
        if (target.getAvailabilityZoneId() != null) {
            node.put("AvailabilityZoneId", target.getAvailabilityZoneId());
        }
        if (target.getAvailabilityZoneName() != null) {
            node.put("AvailabilityZoneName", target.getAvailabilityZoneName());
        }
        if (target.getVpcId() != null) {
            node.put("VpcId", target.getVpcId());
        }
        return node;
    }

    private ObjectNode toAccessPointNode(EfsAccessPoint accessPoint) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("ClientToken", accessPoint.getClientToken());
        node.put("AccessPointId", accessPoint.getAccessPointId());
        node.put("AccessPointArn", accessPoint.getAccessPointArn());
        node.put("FileSystemId", accessPoint.getFileSystemId());
        node.put("OwnerId", accessPoint.getOwnerId());
        node.put("LifeCycleState", accessPoint.getLifeCycleState());
        node.set("Tags", tagsArray(accessPoint.getTags()));
        String name = accessPoint.getTags().get("Name");
        if (name != null) {
            node.put("Name", name);
        }
        if (accessPoint.getPosixUser() != null) {
            node.set("PosixUser", objectMapper.valueToTree(accessPoint.getPosixUser()));
        }
        if (accessPoint.getRootDirectory() != null) {
            node.set("RootDirectory", objectMapper.valueToTree(accessPoint.getRootDirectory()));
        }
        return node;
    }

    private ArrayNode tagsArray(Map<String, String> tags) {
        ArrayNode array = objectMapper.createArrayNode();
        tags.forEach((key, value) -> {
            ObjectNode tag = objectMapper.createObjectNode();
            tag.put("Key", key);
            tag.put("Value", value);
            array.add(tag);
        });
        return array;
    }

    private static AwsException translateEc2(AwsException e, String subnetId) {
        return switch (e.getErrorCode()) {
            case "InvalidSubnetID.NotFound" -> efsError("SubnetNotFound",
                    "The subnet ID '" + subnetId + "' does not exist.", 400);
            case "InvalidGroup.NotFound" -> efsError("SecurityGroupNotFound", e.getMessage(), 400);
            case "InvalidParameterValue", "InvalidAddress.NotFound" -> efsError("IpAddressInUse",
                    e.getMessage(), 409);
            default -> e;
        };
    }

    private static AwsException efsError(String code, String message, int status) {
        return efsError(code, message, status, Map.of("ErrorCode", code));
    }

    private static AwsException efsError(String code, String message, int status, Map<String, Object> extra) {
        return new AwsException(code, message, status, extra);
    }

    private static String storageKey(String region, String id) {
        return region + "::" + id;
    }

    private static String requireText(JsonNode request, String field) {
        if (request == null || request.isMissingNode() || request.isNull() || !request.hasNonNull(field)) {
            throw efsError("BadRequest", field + " is required.", 400);
        }
        String value = request.get(field).asText();
        if (value == null || value.isBlank()) {
            throw efsError("BadRequest", field + " is required.", 400);
        }
        return value;
    }

    private static String textOr(JsonNode request, String field, String fallback) {
        String value = textOrNull(request, field);
        return value != null ? value : fallback;
    }

    private static String textOrNull(JsonNode request, String field) {
        if (request == null || !request.hasNonNull(field)) {
            return null;
        }
        String value = request.get(field).asText();
        return value == null || value.isBlank() ? null : value;
    }

    private static Map<String, String> readTags(JsonNode tagsNode) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (tagsNode == null || !tagsNode.isArray()) {
            return tags;
        }
        for (JsonNode tag : tagsNode) {
            if (tag.hasNonNull("Key")) {
                tags.put(tag.get("Key").asText(), tag.path("Value").asText(""));
            }
        }
        return tags;
    }

    private static List<String> readSecurityGroups(JsonNode node) {
        List<String> groups = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return groups;
        }
        for (JsonNode item : node) {
            String value = item.asText();
            if (value != null && !value.isBlank()) {
                groups.add(value);
            }
        }
        return groups;
    }

    private static String randomHex(int length) {
        StringBuilder hex = new StringBuilder();
        while (hex.length() < length) {
            hex.append(UUID.randomUUID().toString().replace("-", ""));
        }
        return hex.substring(0, length);
    }
}
