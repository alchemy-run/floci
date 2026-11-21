package io.floci.aws.services.s3files;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.common.ServicePrincipals;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.ec2.model.GroupIdentifier;
import io.floci.aws.services.ec2.model.NetworkInterface;
import io.floci.aws.services.ec2.model.SecurityGroup;
import io.floci.aws.services.ec2.model.Subnet;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.model.IamRole;
import io.floci.aws.services.s3.S3BucketDeletionGuard;
import io.floci.aws.services.s3.S3Service;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Provider;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Amazon S3 Files: file systems over a versioned S3 bucket, with access points, mount targets, a resource policy
 * and the synchronization rules that say when objects are imported and when they expire from the file system.
 * The bucket, the role (which the EFS service assumes) and the mount targets' subnets and security groups are
 * checked in S3, IAM and EC2; each mount target holds an EC2 network interface. A bucket with a file system can't
 * be deleted. Like EFS in Floci this is the control plane: there is no NFS endpoint to mount.
 */
@ApplicationScoped
public class S3FilesService implements Resettable, S3BucketDeletionGuard {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AccountAwareStorageBackend<ObjectNode> fileSystems;
    private final AccountAwareStorageBackend<ObjectNode> accessPoints;
    private final AccountAwareStorageBackend<ObjectNode> mountTargets;
    private final ObjectMapper mapper;
    private final EmulatorConfig config;
    private final Provider<S3Service> s3;
    private final Provider<IamService> iam;
    private final Provider<Ec2Service> ec2;

    @Inject
    public S3FilesService(StorageFactory storageFactory, ObjectMapper mapper, EmulatorConfig config,
                          Provider<S3Service> s3, Provider<IamService> iam, Provider<Ec2Service> ec2) {
        this.fileSystems = storageFactory.create("s3files", "file-systems.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.accessPoints = storageFactory.create("s3files", "access-points.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.mountTargets = storageFactory.create("s3files", "mount-targets.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.mapper = mapper;
        this.config = config;
        this.s3 = s3;
        this.iam = iam;
        this.ec2 = ec2;
    }

    @Override
    public void clear() {
        fileSystems.clear();
        accessPoints.clear();
        mountTargets.clear();
    }

    public ObjectNode handle(String operation, JsonNode in, String region) {
        return switch (operation) {
            case "CreateFileSystem" -> createFileSystem(in, region);
            case "GetFileSystem" -> view(fileSystem(region, required(in, "fileSystemId")));
            case "ListFileSystems" -> listFileSystems(in, region);
            case "DeleteFileSystem" -> deleteFileSystem(in, region);
            case "PutFileSystemPolicy" -> putPolicy(in, region);
            case "GetFileSystemPolicy" -> getPolicy(in, region);
            case "DeleteFileSystemPolicy" -> deletePolicy(in, region);
            case "PutSynchronizationConfiguration" -> putSynchronization(in, region);
            case "GetSynchronizationConfiguration" -> getSynchronization(in, region);
            case "CreateAccessPoint" -> createAccessPoint(in, region);
            case "GetAccessPoint" -> view(settled(accessPoints, region, required(in, "accessPointId"),
                    "access point"));
            case "ListAccessPoints" -> listAccessPoints(in, region);
            case "DeleteAccessPoint" -> deleteAccessPoint(in, region);
            case "CreateMountTarget" -> createMountTarget(in, region);
            case "GetMountTarget" -> view(settled(mountTargets, region, required(in, "mountTargetId"),
                    "mount target"));
            case "UpdateMountTarget" -> updateMountTarget(in, region);
            case "ListMountTargets" -> listMountTargets(in, region);
            case "DeleteMountTarget" -> deleteMountTarget(in, region);
            case "TagResource" -> tag(in, region, true);
            case "UntagResource" -> tag(in, region, false);
            case "ListTagsForResource" -> listTags(in, region);
            default -> throw new AwsException("UnknownOperationException", "Unknown operation " + operation + ".",
                    404);
        };
    }

    // ---------- file systems ----------

    private ObjectNode createFileSystem(JsonNode in, String region) {
        String token = in.path("clientToken").asText(null);
        if (token != null) {
            for (ObjectNode existing : live(fileSystems, region)) {
                if (token.equals(existing.path("clientToken").asText())) {
                    return view(existing);
                }
            }
        }
        String bucketArn = required(in, "bucket");
        String bucket = bucketName(bucketArn);
        String prefix = in.path("prefix").asText("");
        if (!s3.get().bucketExists(bucket)) {
            throw notFound("The bucket " + bucket + " doesn't exist.");
        }
        if (!"Enabled".equals(s3.get().getBucketVersioning(bucket))) {
            throw validation("The bucket " + bucket + " must have versioning enabled to back a file system.");
        }
        String roleArn = required(in, "roleArn");
        requireRole(roleArn);
        for (ObjectNode existing : live(fileSystems, region)) {
            if (bucket.equals(bucketName(existing.path("bucket").asText()))
                    && overlaps(prefix, existing.path("prefix").asText(""))) {
                throw conflict("The file system " + existing.path("fileSystemId").asText()
                        + " already covers this bucket and prefix.");
            }
        }
        String id = "fs-" + hex(9);
        String account = fileSystems.accountId();
        ObjectNode fs = record(region, "fileSystemId", id);
        fs.put("fileSystemArn", AwsArnUtils.Arn.of("s3files", region, account, "file-system/" + id).toString())
                .put("bucket", bucketArn).put("roleArn", roleArn).put("ownerId", account)
                .put("statusMessage", "Creating the file system");
        if (!prefix.isEmpty()) {
            fs.put("prefix", prefix);
        }
        if (token != null) {
            fs.put("clientToken", token);
        }
        if (in.hasNonNull("kmsKeyId")) {
            fs.put("kmsKeyId", in.get("kmsKeyId").asText());
        }
        fs.set("tags", tagList(in.path("tags")));
        fs.put("name", nameOf(fs));
        ObjectNode meta = (ObjectNode) fs.get("_");
        meta.set("sync", defaultSynchronization());
        fileSystems.put(key(region, id), fs);
        return view(fs);
    }

    private ObjectNode listFileSystems(JsonNode in, String region) {
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode fs : live(fileSystems, region)) {
            if (in.hasNonNull("bucket") && !bucketName(in.get("bucket").asText())
                    .equals(bucketName(fs.path("bucket").asText()))) {
                continue;
            }
            items.add(pick(fs, "creationTime", "fileSystemArn", "fileSystemId", "name", "bucket", "status",
                    "statusMessage", "roleArn", "ownerId"));
        }
        return page(items, in, "fileSystems");
    }

    private ObjectNode deleteFileSystem(JsonNode in, String region) {
        ObjectNode fs = fileSystem(region, required(in, "fileSystemId"));
        String id = fs.path("fileSystemId").asText();
        if ("deleting".equals(fs.path("status").asText())) {
            return mapper.createObjectNode();
        }
        if ("creating".equals(fs.path("status").asText())) {
            throw conflict("The file system " + id + " is still being created.");
        }
        boolean force = in.path("forceDelete").asBoolean(false);
        List<ObjectNode> aps = children(accessPoints, region, id);
        List<ObjectNode> mts = children(mountTargets, region, id);
        if (!force && (!aps.isEmpty() || !mts.isEmpty())) {
            throw conflict("The file system " + id + " has " + aps.size() + " access point(s) and " + mts.size()
                    + " mount target(s). Delete them first, or pass forceDelete.");
        }
        for (ObjectNode ap : aps) {
            accessPoints.delete(key(region, ap.path("accessPointId").asText()));
        }
        for (ObjectNode mt : mts) {
            releaseInterface(region, mt);
            mountTargets.delete(key(region, mt.path("mountTargetId").asText()));
        }
        retire(fileSystems, region, fs, "fileSystemId", "Deleting the file system");
        return mapper.createObjectNode();
    }

    private ObjectNode fileSystem(String region, String id) {
        return settled(fileSystems, region, id, "file system");
    }

    /** A file system that can take access points, mount targets and configuration: present and available. */
    private ObjectNode availableFileSystem(String region, String id) {
        ObjectNode fs = fileSystem(region, id);
        if (!"available".equals(fs.path("status").asText())) {
            throw conflict("The file system " + id + " is " + fs.path("status").asText() + ".");
        }
        return fs;
    }

    private void requireRole(String roleArn) {
        if (!AwsArnUtils.isArnFor(roleArn, "iam") || !AwsArnUtils.parse(roleArn).resource().startsWith("role/")) {
            throw validation("roleArn " + roleArn + " isn't an IAM role ARN.");
        }
        String resource = AwsArnUtils.parse(roleArn).resource();
        IamRole role;
        try {
            role = iam.get().getRole(resource.substring(resource.lastIndexOf('/') + 1));
        } catch (AwsException e) {
            throw validation("The role " + roleArn + " doesn't exist.");
        }
        if (!IamService.roleArnMatches(roleArn, role)) {
            throw validation("The role " + roleArn + " doesn't exist.");
        }
        if (!trustsFileSystems(role.getAssumeRolePolicyDocument())) {
            throw validation("The role " + roleArn + " must trust the " + ServicePrincipals.of("elasticfilesystem")
                    + " service principal, which S3 Files uses to reach the bucket.");
        }
    }

    private boolean trustsFileSystems(String document) {
        if (document == null) {
            return false;
        }
        JsonNode policy;
        try {
            policy = mapper.readTree(document.contains("%") ? URLDecoder.decode(document, StandardCharsets.UTF_8)
                    : document);
        } catch (Exception e) {
            return false;
        }
        JsonNode statements = policy.path("Statement");
        for (JsonNode statement : statements.isArray() ? statements : mapper.createArrayNode().add(statements)) {
            if (!"Allow".equals(statement.path("Effect").asText())) {
                continue;
            }
            JsonNode services = statement.path("Principal").path("Service");
            for (JsonNode service : services.isArray() ? services : mapper.createArrayNode().add(services)) {
                if ("elasticfilesystem".equals(ServicePrincipals.serviceName(service.asText()))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** A bucket with a file system on it, being created, in use or being deleted, can't be deleted. */
    @Override
    public Optional<AwsException> refuseDeletion(String bucketName) {
        for (ObjectNode fs : fileSystems.scan(key -> true)) {
            if (bucketName.equals(bucketName(fs.path("bucket").asText()))) {
                String region = AwsArnUtils.parse(fs.path("fileSystemArn").asText()).region();
                ObjectNode current = settle(fileSystems, region, fs, "fileSystemId");
                if (current != null) {
                    return Optional.of(new AwsException("BucketHasS3FileSystemAttached", "The bucket " + bucketName
                            + " has the S3 file system " + current.path("fileSystemId").asText()
                            + " attached. Delete the file system first.", 409));
                }
            }
        }
        return Optional.empty();
    }

    // ---------- policy and synchronization ----------

    private ObjectNode putPolicy(JsonNode in, String region) {
        ObjectNode fs = availableFileSystem(region, required(in, "fileSystemId"));
        String policy = required(in, "policy");
        JsonNode parsed;
        try {
            parsed = mapper.readTree(policy);
        } catch (Exception e) {
            throw validation("The policy isn't valid JSON.");
        }
        if (!parsed.isObject() || !parsed.has("Statement")) {
            throw validation("The policy needs a Statement.");
        }
        ((ObjectNode) fs.get("_")).put("policy", policy);
        save(fileSystems, region, fs, "fileSystemId");
        return mapper.createObjectNode();
    }

    private ObjectNode getPolicy(JsonNode in, String region) {
        ObjectNode fs = fileSystem(region, required(in, "fileSystemId"));
        JsonNode policy = fs.get("_").get("policy");
        if (policy == null || policy.isNull()) {
            throw notFound("The file system " + fs.path("fileSystemId").asText() + " has no policy.");
        }
        return mapper.createObjectNode().put("fileSystemId", fs.path("fileSystemId").asText())
                .put("policy", policy.asText());
    }

    private ObjectNode deletePolicy(JsonNode in, String region) {
        ObjectNode fs = fileSystem(region, required(in, "fileSystemId"));
        ((ObjectNode) fs.get("_")).remove("policy");
        save(fileSystems, region, fs, "fileSystemId");
        return mapper.createObjectNode();
    }

    private ObjectNode defaultSynchronization() {
        ObjectNode sync = mapper.createObjectNode().put("latestVersionNumber", 1);
        sync.putArray("importDataRules").addObject().put("prefix", "").put("trigger", "ON_DIRECTORY_FIRST_ACCESS")
                .put("sizeLessThan", 131072);
        sync.putArray("expirationDataRules").addObject().put("daysAfterLastAccess", 30);
        return sync;
    }

    private ObjectNode putSynchronization(JsonNode in, String region) {
        ObjectNode fs = availableFileSystem(region, required(in, "fileSystemId"));
        ObjectNode sync = (ObjectNode) fs.get("_").get("sync");
        int version = sync.path("latestVersionNumber").asInt();
        if (in.has("latestVersionNumber") && in.get("latestVersionNumber").asInt() != version) {
            throw conflict("The synchronization configuration is at version " + version + ", not "
                    + in.get("latestVersionNumber").asInt() + ".");
        }
        List<String> prefixes = new ArrayList<>();
        for (JsonNode rule : in.path("importDataRules")) {
            String prefix = rule.path("prefix").asText("");
            if (prefixes.contains(prefix)) {
                throw validation("Two import rules have the prefix \"" + prefix + "\".");
            }
            prefixes.add(prefix);
            if (rule.path("sizeLessThan").asLong(-1) < 0) {
                throw validation("An import rule's sizeLessThan must be zero or more.");
            }
        }
        for (JsonNode rule : in.path("expirationDataRules")) {
            int days = rule.path("daysAfterLastAccess").asInt(0);
            if (days < 1 || days > 365) {
                throw validation("daysAfterLastAccess must be between 1 and 365.");
            }
        }
        ObjectNode next = mapper.createObjectNode().put("latestVersionNumber", version + 1);
        next.set("importDataRules", in.path("importDataRules").deepCopy());
        next.set("expirationDataRules", in.path("expirationDataRules").deepCopy());
        ((ObjectNode) fs.get("_")).set("sync", next);
        save(fileSystems, region, fs, "fileSystemId");
        return mapper.createObjectNode();
    }

    private ObjectNode getSynchronization(JsonNode in, String region) {
        return fileSystem(region, required(in, "fileSystemId")).get("_").get("sync").deepCopy();
    }

    // ---------- access points ----------

    private ObjectNode createAccessPoint(JsonNode in, String region) {
        String token = in.path("clientToken").asText(null);
        if (token != null) {
            for (ObjectNode existing : live(accessPoints, region)) {
                if (token.equals(existing.path("clientToken").asText())) {
                    return view(existing);
                }
            }
        }
        ObjectNode fs = availableFileSystem(region, required(in, "fileSystemId"));
        JsonNode posix = in.path("posixUser");
        if (!posix.isMissingNode() && (posix.path("uid").asLong(-1) < 0 || posix.path("gid").asLong(-1) < 0)) {
            throw validation("posixUser needs a uid and gid of zero or more.");
        }
        JsonNode root = in.path("rootDirectory");
        if (!root.isMissingNode()) {
            String path = root.path("path").asText("/");
            if (!path.startsWith("/") || path.length() > 100 || path.contains("//")) {
                throw validation("The root directory path must be an absolute path of at most 100 characters.");
            }
            JsonNode permissions = root.path("creationPermissions");
            if (!permissions.isMissingNode() && !permissions.path("permissions").asText().matches("0?[0-7]{3}")) {
                throw validation("creationPermissions.permissions must be an octal mode such as 0755.");
            }
        }
        String id = "fsap-" + hex(9);
        String account = accessPoints.accountId();
        String fsId = fs.path("fileSystemId").asText();
        ObjectNode ap = record(region, "accessPointId", id);
        ap.put("accessPointArn", AwsArnUtils.Arn.of("s3files", region, account, "file-system/" + fsId
                        + "/access-point/" + id).toString())
                .put("clientToken", token == null ? id : token).put("fileSystemId", fsId).put("ownerId", account);
        if (!posix.isMissingNode()) {
            ap.set("posixUser", posix.deepCopy());
        }
        ap.set("rootDirectory", root.isMissingNode() ? mapper.createObjectNode().put("path", "/") : root.deepCopy());
        ap.set("tags", tagList(in.path("tags")));
        ap.put("name", nameOf(ap));
        accessPoints.put(key(region, id), ap);
        return view(ap);
    }

    private ObjectNode listAccessPoints(JsonNode in, String region) {
        String fsId = fileSystem(region, required(in, "fileSystemId")).path("fileSystemId").asText();
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode ap : children(accessPoints, region, fsId)) {
            items.add(pick(ap, "accessPointArn", "accessPointId", "fileSystemId", "status", "ownerId", "posixUser",
                    "rootDirectory", "name"));
        }
        return page(items, in, "accessPoints");
    }

    private ObjectNode deleteAccessPoint(JsonNode in, String region) {
        ObjectNode ap = settled(accessPoints, region, required(in, "accessPointId"), "access point");
        if (!"deleting".equals(ap.path("status").asText())) {
            retire(accessPoints, region, ap, "accessPointId", null);
        }
        return mapper.createObjectNode();
    }

    // ---------- mount targets ----------

    private ObjectNode createMountTarget(JsonNode in, String region) {
        ObjectNode fs = availableFileSystem(region, required(in, "fileSystemId"));
        String fsId = fs.path("fileSystemId").asText();
        Subnet subnet;
        try {
            subnet = ec2.get().requireSubnet(region, required(in, "subnetId"));
        } catch (AwsException e) {
            throw validation("The subnet " + in.path("subnetId").asText() + " doesn't exist.");
        }
        for (ObjectNode existing : children(mountTargets, region, fsId)) {
            if (!subnet.getVpcId().equals(existing.path("vpcId").asText())) {
                throw conflict("The file system's mount targets are in " + existing.path("vpcId").asText()
                        + ". All of them must be in one VPC.");
            }
            if (subnet.getAvailabilityZoneId() != null
                    && subnet.getAvailabilityZoneId().equals(existing.path("availabilityZoneId").asText())) {
                throw conflict("The file system already has the mount target "
                        + existing.path("mountTargetId").asText() + " in " + subnet.getAvailabilityZone() + ".");
            }
        }
        List<String> groups = strings(in.path("securityGroups"));
        requireSecurityGroups(region, subnet.getVpcId(), groups);
        String id = "fsmt-" + hex(9);
        NetworkInterface eni;
        try {
            eni = ec2.get().createNetworkInterface(region, subnet.getSubnetId(), "S3 Files mount target " + id,
                    in.path("ipv4Address").asText(null), List.of(), groups, List.of());
        } catch (AwsException e) {
            throw validation(e.getMessage());
        }
        ObjectNode mt = record(region, "mountTargetId", id);
        mt.put("fileSystemId", fsId).put("subnetId", subnet.getSubnetId()).put("vpcId", subnet.getVpcId())
                .put("availabilityZoneId", subnet.getAvailabilityZoneId())
                .put("ipv4Address", eni.getPrivateIpAddress()).put("networkInterfaceId", eni.getNetworkInterfaceId())
                .put("ownerId", mountTargets.accountId()).put("statusMessage", "Creating the mount target");
        ArrayNode attached = mt.putArray("securityGroups");
        eni.getGroups().stream().map(GroupIdentifier::getGroupId).forEach(attached::add);
        mountTargets.put(key(region, id), mt);
        return view(mt);
    }

    private ObjectNode updateMountTarget(JsonNode in, String region) {
        ObjectNode mt = settled(mountTargets, region, required(in, "mountTargetId"), "mount target");
        List<String> groups = strings(in.path("securityGroups"));
        requireSecurityGroups(region, mt.path("vpcId").asText(), groups);
        try {
            ec2.get().modifyNetworkInterfaceAttribute(region, mt.path("networkInterfaceId").asText(), null, null,
                    groups);
        } catch (AwsException e) {
            throw validation(e.getMessage());
        }
        ArrayNode attached = mt.putArray("securityGroups");
        groups.forEach(attached::add);
        save(mountTargets, region, mt, "mountTargetId");
        return view(mt);
    }

    private ObjectNode listMountTargets(JsonNode in, String region) {
        String fsId = in.path("fileSystemId").asText(null);
        if (in.hasNonNull("accessPointId")) {
            fsId = settled(accessPoints, region, in.get("accessPointId").asText(), "access point")
                    .path("fileSystemId").asText();
        }
        if (fsId != null) {
            fileSystem(region, fsId);
        }
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode mt : live(mountTargets, region)) {
            if (fsId == null || fsId.equals(mt.path("fileSystemId").asText())) {
                items.add(pick(mt, "availabilityZoneId", "fileSystemId", "ipv4Address", "ipv6Address", "status",
                        "statusMessage", "mountTargetId", "networkInterfaceId", "ownerId", "subnetId", "vpcId"));
            }
        }
        return page(items, in, "mountTargets");
    }

    private ObjectNode deleteMountTarget(JsonNode in, String region) {
        ObjectNode mt = settled(mountTargets, region, required(in, "mountTargetId"), "mount target");
        if (!"deleting".equals(mt.path("status").asText())) {
            releaseInterface(region, mt);
            retire(mountTargets, region, mt, "mountTargetId", "Deleting the mount target");
        }
        return mapper.createObjectNode();
    }

    private void requireSecurityGroups(String region, String vpcId, List<String> groups) {
        if (groups.size() > 5) {
            throw validation("A mount target takes at most 5 security groups.");
        }
        if (groups.isEmpty()) {
            return;
        }
        List<SecurityGroup> found;
        try {
            found = ec2.get().describeSecurityGroups(region, groups, List.of(), Map.of());
        } catch (AwsException e) {
            throw validation("A security group doesn't exist: " + e.getMessage());
        }
        for (SecurityGroup group : found) {
            if (!vpcId.equals(group.getVpcId())) {
                throw validation("The security group " + group.getGroupId() + " isn't in " + vpcId + ".");
            }
        }
    }

    private void releaseInterface(String region, ObjectNode mt) {
        try {
            ec2.get().deleteNetworkInterface(region, mt.path("networkInterfaceId").asText());
        } catch (AwsException ignored) {
            // Already gone.
        }
    }

    // ---------- tags ----------

    private ObjectNode tag(JsonNode in, String region, boolean add) {
        Tagged target = tagged(region, decode(required(in, "resourceId")));
        ArrayNode tags = (ArrayNode) target.record().get("tags");
        if (add) {
            for (JsonNode tag : in.path("tags")) {
                removeTag(tags, tag.path("key").asText());
                tags.add(tag.deepCopy());
            }
            if (tags.size() > 50) {
                throw validation("A resource can have at most 50 tags.");
            }
        } else {
            for (JsonNode key : in.path("tagKeys")) {
                removeTag(tags, key.asText());
            }
        }
        target.record().put("name", nameOf(target.record()));
        save(target.store(), region, target.record(), target.idField());
        return mapper.createObjectNode();
    }

    private ObjectNode listTags(JsonNode in, String region) {
        Tagged target = tagged(region, decode(required(in, "resourceId")));
        List<ObjectNode> tags = new ArrayList<>();
        target.record().get("tags").forEach(tag -> tags.add((ObjectNode) tag.deepCopy()));
        return page(tags, in, "tags");
    }

    private record Tagged(AccountAwareStorageBackend<ObjectNode> store, ObjectNode record, String idField) {
    }

    private Tagged tagged(String region, String resourceId) {
        String id = resourceId.contains("/") ? resourceId.substring(resourceId.lastIndexOf('/') + 1) : resourceId;
        if (id.startsWith("fsap-")) {
            return new Tagged(accessPoints, settled(accessPoints, region, id, "access point"), "accessPointId");
        }
        return new Tagged(fileSystems, fileSystem(region, id), "fileSystemId");
    }

    private static void removeTag(ArrayNode tags, String key) {
        for (int i = tags.size() - 1; i >= 0; i--) {
            if (key.equals(tags.get(i).path("key").asText())) {
                tags.remove(i);
            }
        }
    }

    private static String nameOf(ObjectNode record) {
        for (JsonNode tag : record.path("tags")) {
            if ("Name".equals(tag.path("key").asText())) {
                return tag.path("value").asText();
            }
        }
        return null;
    }

    // ---------- records and their lifecycle ----------

    private ObjectNode record(String region, String idField, String id) {
        ObjectNode record = mapper.createObjectNode();
        record.put(idField, id).put("status", "creating").put("creationTime", now());
        record.putObject("_").put("ready", now() + step()).put("region", region);
        return record;
    }

    /** The record with any creation or deletion that is due applied, or null once it's gone. */
    private ObjectNode settle(AccountAwareStorageBackend<ObjectNode> store, String region, ObjectNode stored,
                              String idField) {
        ObjectNode record = stored.deepCopy();
        ObjectNode meta = (ObjectNode) record.get("_");
        String status = record.path("status").asText();
        if ("creating".equals(status) && now() >= meta.path("ready").asDouble()) {
            record.put("status", "available").remove("statusMessage");
            store.put(key(region, record.path(idField).asText()), record);
        } else if ("deleting".equals(status) && now() >= meta.path("gone").asDouble()) {
            store.delete(key(region, record.path(idField).asText()));
            return null;
        }
        return record;
    }

    private ObjectNode settled(AccountAwareStorageBackend<ObjectNode> store, String region, String id, String type) {
        String field = idField(store);
        ObjectNode record = store.get(key(region, id)).map(stored -> settle(store, region, stored, field))
                .orElse(null);
        if (record == null) {
            throw notFound("The " + type + " " + id + " doesn't exist.");
        }
        return record;
    }

    private String idField(AccountAwareStorageBackend<ObjectNode> store) {
        return store == fileSystems ? "fileSystemId" : store == accessPoints ? "accessPointId" : "mountTargetId";
    }

    /** Every record of the region that isn't deleting, settled, oldest first. */
    private List<ObjectNode> live(AccountAwareStorageBackend<ObjectNode> store, String region) {
        String field = idField(store);
        List<ObjectNode> out = new ArrayList<>();
        for (ObjectNode stored : store.scan(k -> k.startsWith(region + "/"))) {
            ObjectNode record = settle(store, region, stored, field);
            if (record != null && !"deleting".equals(record.path("status").asText())) {
                out.add(record);
            }
        }
        out.sort(Comparator.comparingDouble((ObjectNode r) -> r.path("creationTime").asDouble())
                .thenComparing(r -> r.path(field).asText()));
        return out;
    }

    private List<ObjectNode> children(AccountAwareStorageBackend<ObjectNode> store, String region, String fsId) {
        return live(store, region).stream().filter(r -> fsId.equals(r.path("fileSystemId").asText())).toList();
    }

    private void retire(AccountAwareStorageBackend<ObjectNode> store, String region, ObjectNode record,
                        String idField, String message) {
        ((ObjectNode) record.get("_")).put("gone", now() + step());
        record.put("status", "deleting");
        if (message != null) {
            record.put("statusMessage", message);
        }
        save(store, region, record, idField);
    }

    private void save(AccountAwareStorageBackend<ObjectNode> store, String region, ObjectNode record,
                      String idField) {
        store.put(key(region, record.path(idField).asText()), record);
    }

    private ObjectNode view(ObjectNode record) {
        ObjectNode view = record.deepCopy();
        view.remove("_");
        if (view.path("name").isNull()) {
            view.remove("name");
        }
        return view;
    }

    private ObjectNode pick(ObjectNode record, String... fields) {
        ObjectNode out = mapper.createObjectNode();
        for (String field : fields) {
            if (record.hasNonNull(field)) {
                out.set(field, record.get(field).deepCopy());
            }
        }
        return out;
    }

    private ArrayNode tagList(JsonNode tags) {
        ArrayNode list = mapper.createArrayNode();
        for (JsonNode tag : tags) {
            removeTag(list, tag.path("key").asText());
            list.add(tag.deepCopy());
        }
        return list;
    }

    private ObjectNode page(List<ObjectNode> items, JsonNode in, String field) {
        int max = in.path("maxResults").asInt(100);
        int start = 0;
        if (in.hasNonNull("nextToken")) {
            try {
                start = Integer.parseInt(new String(Base64.getUrlDecoder().decode(in.get("nextToken").asText()),
                        StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                throw validation("The nextToken is not valid.");
            }
        }
        start = Math.max(0, Math.min(start, items.size()));
        int end = Math.min(items.size(), start + Math.max(1, max));
        ObjectNode out = mapper.createObjectNode();
        ArrayNode array = out.putArray(field);
        items.subList(start, end).forEach(array::add);
        if (end < items.size()) {
            out.put("nextToken", Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(String.valueOf(end).getBytes(StandardCharsets.UTF_8)));
        }
        return out;
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(value -> out.add(value.asText()));
        return out;
    }

    /** Two prefixes of one bucket overlap when either contains the other; a file system covers its prefix. */
    private static boolean overlaps(String a, String b) {
        return a.startsWith(b) || b.startsWith(a);
    }

    private static String bucketName(String bucket) {
        if (AwsArnUtils.isArnFor(bucket, "s3")) {
            String resource = AwsArnUtils.parse(bucket).resource();
            return resource.contains("/") ? resource.substring(0, resource.indexOf('/')) : resource;
        }
        return bucket;
    }

    /** The transition time, in seconds. */
    private double step() {
        return config.services().s3files().transitionMillis() / 1000.0;
    }

    private static double now() {
        return Instant.now().toEpochMilli() / 1000.0;
    }

    private static String key(String region, String id) {
        return region + "/" + id;
    }

    private static String hex(int bytes) {
        byte[] random = new byte[bytes];
        RANDOM.nextBytes(random);
        return HexFormat.of().formatHex(random).substring(0, 17);
    }

    private static String decode(String value) {
        return value.contains("%") ? URLDecoder.decode(value, StandardCharsets.UTF_8) : value;
    }

    private static String required(JsonNode in, String field) {
        String value = in.path(field).asText(null);
        if (value == null || value.isBlank()) {
            throw validation(field + " is required.");
        }
        return value;
    }

    private static AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 404);
    }

    private static AwsException conflict(String message) {
        return new AwsException("ConflictException", message, 409);
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }
}
