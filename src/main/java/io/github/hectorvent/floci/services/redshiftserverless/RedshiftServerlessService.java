package io.github.hectorvent.floci.services.redshiftserverless;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.services.secretsmanager.SecretsManagerService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Local Amazon Redshift Serverless control plane. Namespaces and workgroups
 * become {@code AVAILABLE} immediately so Alchemy's bounded wait loops converge.
 */
@ApplicationScoped
public class RedshiftServerlessService implements Resettable {

    static final class Namespace {
        String namespaceId;
        String namespaceArn;
        String namespaceName;
        String adminUsername;
        String adminUserPassword;
        String dbName;
        String kmsKeyId;
        String defaultIamRoleArn;
        List<String> iamRoles = new ArrayList<>();
        List<String> logExports = new ArrayList<>();
        String status = "AVAILABLE";
        String creationDate;
        String adminPasswordSecretArn;
        String adminPasswordSecretKmsKeyId;
        String region;
        final Map<String, String> tags = new LinkedHashMap<>();
    }

    static final class Workgroup {
        String workgroupId;
        String workgroupArn;
        String workgroupName;
        String namespaceName;
        int baseCapacity = 8;
        Integer maxCapacity;
        boolean enhancedVpcRouting;
        boolean publiclyAccessible;
        int port = 5439;
        List<String> subnetIds = new ArrayList<>();
        List<String> securityGroupIds = new ArrayList<>();
        List<Map<String, String>> configParameters = new ArrayList<>();
        String status = "AVAILABLE";
        String creationDate;
        String region;
        String endpointAddress;
        final Map<String, String> tags = new LinkedHashMap<>();
    }

    static final class Snapshot {
        String snapshotName;
        String snapshotArn;
        String namespaceName;
        String namespaceArn;
        String adminUsername;
        String status = "AVAILABLE";
        String kmsKeyId;
        String ownerAccount;
        String snapshotCreateTime;
        int snapshotRetentionPeriod = 1;
        int snapshotRemainingDays = 1;
        String snapshotRetentionStartTime;
        String adminPasswordSecretArn;
        String adminPasswordSecretKmsKeyId;
        String region;
        final Map<String, String> tags = new LinkedHashMap<>();
    }

    private static final String PASSWORD_ALPHABET =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!#-";
    private static final SecureRandom RANDOM = new SecureRandom();
    static final int DEFAULT_CREDENTIAL_DURATION_SECONDS = 900;
    static final int MIN_CREDENTIAL_DURATION_SECONDS = 900;
    static final int MAX_CREDENTIAL_DURATION_SECONDS = 3600;

    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;
    private final SecretsManagerService secretsManagerService;
    private final ConcurrentHashMap<String, Namespace> namespaces = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Workgroup> workgroups = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Snapshot> snapshots = new ConcurrentHashMap<>();

    @Inject
    public RedshiftServerlessService(ObjectMapper objectMapper,
                                     RegionResolver regionResolver,
                                     SecretsManagerService secretsManagerService) {
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
        this.secretsManagerService = secretsManagerService;
    }

    @Override
    public void clear() {
        namespaces.clear();
        workgroups.clear();
        snapshots.clear();
    }

    Namespace requireNamespace(String name) {
        Namespace namespace = namespaces.get(name);
        if (namespace == null) {
            throw notFound("Namespace " + name + " was not found.");
        }
        return namespace;
    }

    Workgroup requireWorkgroup(String name) {
        Workgroup workgroup = workgroups.get(name);
        if (workgroup == null) {
            throw notFound("Workgroup " + name + " was not found.");
        }
        return workgroup;
    }

    Snapshot requireSnapshot(String name) {
        Snapshot snapshot = snapshots.get(name);
        if (snapshot == null) {
            throw notFound("Snapshot " + name + " was not found.");
        }
        return snapshot;
    }

    public String namespaceNameForWorkgroup(String workgroupName) {
        return requireWorkgroup(workgroupName).namespaceName;
    }

    public String dbNameForNamespace(String namespaceName) {
        return requireNamespace(namespaceName).dbName;
    }

    public String namespaceId(String namespaceName) {
        return requireNamespace(namespaceName).namespaceId;
    }

    public ObjectNode createNamespace(JsonNode request, String region) {
        String name = requireText(request, "namespaceName");
        if (namespaces.containsKey(name)) {
            throw conflict("A namespace with the name " + name + " already exists.");
        }
        Namespace namespace = new Namespace();
        namespace.namespaceName = name;
        namespace.namespaceId = UUID.randomUUID().toString();
        namespace.namespaceArn = arn(region, "namespace/" + name);
        namespace.dbName = textOrDefault(request, "dbName", "dev");
        namespace.adminUsername = textOrDefault(request, "adminUsername", "admin");
        namespace.kmsKeyId = textOrNull(request, "kmsKeyId");
        namespace.defaultIamRoleArn = textOrNull(request, "defaultIamRoleArn");
        namespace.iamRoles = stringList(request, "iamRoles");
        namespace.logExports = stringList(request, "logExports");
        namespace.region = region;
        namespace.creationDate = Instant.now().toString();
        applyTags(namespace.tags, request.get("tags"));

        boolean manageAdminPassword = request.path("manageAdminPassword").asBoolean(false);
        namespace.adminPasswordSecretKmsKeyId = textOrNull(request, "adminPasswordSecretKmsKeyId");
        if (manageAdminPassword) {
            String password = generatePassword();
            namespace.adminUserPassword = password;
            namespace.adminPasswordSecretArn = putAdminSecret(name, namespace.adminUsername, password, region);
        } else {
            namespace.adminUserPassword = textOrNull(request, "adminUserPassword");
        }

        namespaces.put(name, namespace);
        return envelope("namespace", toNamespaceJson(namespace));
    }

    public ObjectNode getNamespace(JsonNode request) {
        return envelope("namespace", toNamespaceJson(requireNamespace(requireText(request, "namespaceName"))));
    }

    public ObjectNode updateNamespace(JsonNode request) {
        Namespace namespace = requireNamespace(requireText(request, "namespaceName"));
        if (request.hasNonNull("adminUsername")) {
            namespace.adminUsername = request.get("adminUsername").asText();
        }
        if (request.hasNonNull("adminUserPassword")) {
            namespace.adminUserPassword = request.get("adminUserPassword").asText();
        }
        if (request.hasNonNull("kmsKeyId")) {
            namespace.kmsKeyId = request.get("kmsKeyId").asText();
        }
        if (request.hasNonNull("defaultIamRoleArn")) {
            namespace.defaultIamRoleArn = request.get("defaultIamRoleArn").asText();
        }
        if (request.has("iamRoles")) {
            namespace.iamRoles = stringList(request, "iamRoles");
        }
        if (request.has("logExports")) {
            namespace.logExports = stringList(request, "logExports");
        }
        if (request.path("manageAdminPassword").asBoolean(false) && namespace.adminPasswordSecretArn == null) {
            String password = generatePassword();
            namespace.adminUserPassword = password;
            namespace.adminPasswordSecretKmsKeyId = textOrNull(request, "adminPasswordSecretKmsKeyId");
            namespace.adminPasswordSecretArn = putAdminSecret(
                    namespace.namespaceName, namespace.adminUsername, password, namespace.region);
        }
        return envelope("namespace", toNamespaceJson(namespace));
    }

    public ObjectNode deleteNamespace(JsonNode request) {
        String name = requireText(request, "namespaceName");
        Namespace namespace = requireNamespace(name);
        for (Workgroup workgroup : workgroups.values()) {
            if (name.equals(workgroup.namespaceName)) {
                throw conflict("There are workgroups associated with the namespace " + name + ".");
            }
        }
        namespaces.remove(name);
        return envelope("namespace", toNamespaceJson(namespace));
    }

    public ObjectNode listNamespaces() {
        ArrayNode list = objectMapper.createArrayNode();
        for (Namespace namespace : namespaces.values()) {
            list.add(toNamespaceJson(namespace));
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.set("namespaces", list);
        return response;
    }

    public ObjectNode createWorkgroup(JsonNode request, String region) {
        String name = requireText(request, "workgroupName");
        String namespaceName = requireText(request, "namespaceName");
        requireNamespace(namespaceName);
        if (workgroups.containsKey(name)) {
            throw conflict("A workgroup with the name " + name + " already exists.");
        }
        Workgroup workgroup = new Workgroup();
        workgroup.workgroupName = name;
        workgroup.workgroupId = UUID.randomUUID().toString();
        workgroup.workgroupArn = arn(region, "workgroup/" + name);
        workgroup.namespaceName = namespaceName;
        workgroup.baseCapacity = request.path("baseCapacity").asInt(8);
        if (request.hasNonNull("maxCapacity")) {
            workgroup.maxCapacity = request.get("maxCapacity").asInt();
        }
        workgroup.enhancedVpcRouting = request.path("enhancedVpcRouting").asBoolean(false);
        workgroup.publiclyAccessible = request.path("publiclyAccessible").asBoolean(false);
        workgroup.port = request.path("port").asInt(5439);
        workgroup.subnetIds = stringList(request, "subnetIds");
        workgroup.securityGroupIds = stringList(request, "securityGroupIds");
        workgroup.configParameters = configParameters(request.get("configParameters"));
        workgroup.region = region;
        workgroup.creationDate = Instant.now().toString();
        workgroup.endpointAddress = name + "." + regionResolver.getAccountId() + "." + region
                + ".redshift-serverless.amazonaws.com";
        applyTags(workgroup.tags, request.get("tags"));
        workgroups.put(name, workgroup);
        return envelope("workgroup", toWorkgroupJson(workgroup));
    }

    public ObjectNode getWorkgroup(JsonNode request) {
        return envelope("workgroup", toWorkgroupJson(requireWorkgroup(requireText(request, "workgroupName"))));
    }

    public ObjectNode updateWorkgroup(JsonNode request) {
        Workgroup workgroup = requireWorkgroup(requireText(request, "workgroupName"));
        if (request.hasNonNull("baseCapacity")) {
            workgroup.baseCapacity = request.get("baseCapacity").asInt();
        }
        if (request.hasNonNull("maxCapacity")) {
            workgroup.maxCapacity = request.get("maxCapacity").asInt();
        }
        if (request.hasNonNull("enhancedVpcRouting")) {
            workgroup.enhancedVpcRouting = request.get("enhancedVpcRouting").asBoolean();
        }
        if (request.hasNonNull("publiclyAccessible")) {
            workgroup.publiclyAccessible = request.get("publiclyAccessible").asBoolean();
        }
        if (request.hasNonNull("port")) {
            workgroup.port = request.get("port").asInt();
        }
        if (request.has("subnetIds")) {
            workgroup.subnetIds = stringList(request, "subnetIds");
        }
        if (request.has("securityGroupIds")) {
            workgroup.securityGroupIds = stringList(request, "securityGroupIds");
        }
        if (request.has("configParameters")) {
            workgroup.configParameters = configParameters(request.get("configParameters"));
        }
        return envelope("workgroup", toWorkgroupJson(workgroup));
    }

    public ObjectNode deleteWorkgroup(JsonNode request) {
        String name = requireText(request, "workgroupName");
        Workgroup workgroup = requireWorkgroup(name);
        workgroups.remove(name);
        return envelope("workgroup", toWorkgroupJson(workgroup));
    }

    public ObjectNode listWorkgroups() {
        ArrayNode list = objectMapper.createArrayNode();
        for (Workgroup workgroup : workgroups.values()) {
            list.add(toWorkgroupJson(workgroup));
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.set("workgroups", list);
        return response;
    }

    public ObjectNode getCredentials(JsonNode request) {
        return getCredentials(request, "IAM:root");
    }

    /**
     * {@code GetCredentials} mints short-lived pgwire credentials mapped to the
     * caller's IAM identity ({@code IAM:} / {@code IAMR:}), matching live
     * Redshift Serverless. Default duration is 900 seconds (15 minutes).
     */
    public ObjectNode getCredentials(JsonNode request, String dbUser) {
        requireWorkgroup(requireText(request, "workgroupName"));
        int duration = DEFAULT_CREDENTIAL_DURATION_SECONDS;
        if (request.hasNonNull("durationSeconds")) {
            duration = request.get("durationSeconds").asInt();
            if (duration < MIN_CREDENTIAL_DURATION_SECONDS
                    || duration > MAX_CREDENTIAL_DURATION_SECONDS) {
                throw validation("DurationSeconds must be between 900 and 3600.");
            }
        }
        Instant expiration = Instant.now().plusSeconds(duration);
        Instant nextRefresh = expiration.minusSeconds(Math.min(300, Math.max(0, duration / 3)));
        if (nextRefresh.isBefore(Instant.now())) {
            nextRefresh = expiration;
        }
        String user = dbUser == null || dbUser.isBlank() ? "IAM:root" : dbUser;
        if (!user.regionMatches(true, 0, "IAM", 0, 3)) {
            user = "IAM:" + user;
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.put("dbUser", user);
        response.put("dbPassword", generatePassword());
        response.put("expiration", expiration.getEpochSecond());
        response.put("nextRefreshTime", nextRefresh.getEpochSecond());
        return response;
    }

    public ObjectNode createSnapshot(JsonNode request, String region) {
        String snapshotName = requireText(request, "snapshotName");
        Namespace namespace = requireNamespace(requireText(request, "namespaceName"));
        if (snapshots.containsKey(snapshotName)) {
            throw conflict("A snapshot with the name " + snapshotName + " already exists.");
        }
        String now = Instant.now().toString();
        Snapshot snapshot = new Snapshot();
        snapshot.snapshotName = snapshotName;
        snapshot.snapshotArn = arn(region, "snapshot/" + snapshotName);
        snapshot.namespaceName = namespace.namespaceName;
        snapshot.namespaceArn = namespace.namespaceArn;
        snapshot.adminUsername = namespace.adminUsername;
        snapshot.kmsKeyId = namespace.kmsKeyId;
        snapshot.adminPasswordSecretArn = namespace.adminPasswordSecretArn;
        snapshot.adminPasswordSecretKmsKeyId = namespace.adminPasswordSecretKmsKeyId;
        snapshot.ownerAccount = regionResolver.getAccountId();
        snapshot.snapshotCreateTime = now;
        snapshot.snapshotRetentionStartTime = now;
        snapshot.snapshotRetentionPeriod = request.path("retentionPeriod").asInt(1);
        snapshot.snapshotRemainingDays = snapshot.snapshotRetentionPeriod;
        snapshot.region = region;
        applyTags(snapshot.tags, request.get("tags"));
        snapshots.put(snapshotName, snapshot);
        return envelope("snapshot", toSnapshotJson(snapshot));
    }

    public ObjectNode getSnapshot(JsonNode request) {
        return envelope("snapshot", toSnapshotJson(resolveSnapshot(request)));
    }

    public ObjectNode listSnapshots(JsonNode request) {
        String namespaceName = textOrNull(request, "namespaceName");
        String namespaceArn = textOrNull(request, "namespaceArn");
        String ownerAccount = textOrNull(request, "ownerAccount");
        ArrayNode list = objectMapper.createArrayNode();
        for (Snapshot snapshot : snapshots.values()) {
            if (namespaceName != null && !namespaceName.equals(snapshot.namespaceName)) {
                continue;
            }
            if (namespaceArn != null && !namespaceArn.equals(snapshot.namespaceArn)) {
                continue;
            }
            if (ownerAccount != null && !ownerAccount.equals(snapshot.ownerAccount)) {
                continue;
            }
            list.add(toSnapshotJson(snapshot));
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.set("snapshots", list);
        return response;
    }

    public ObjectNode updateSnapshot(JsonNode request) {
        Snapshot snapshot = requireSnapshot(requireText(request, "snapshotName"));
        if (request.hasNonNull("retentionPeriod")) {
            snapshot.snapshotRetentionPeriod = request.get("retentionPeriod").asInt();
            snapshot.snapshotRemainingDays = snapshot.snapshotRetentionPeriod;
        }
        return envelope("snapshot", toSnapshotJson(snapshot));
    }

    public ObjectNode deleteSnapshot(JsonNode request) {
        Snapshot snapshot = requireSnapshot(requireText(request, "snapshotName"));
        snapshots.remove(snapshot.snapshotName);
        snapshot.status = "DELETED";
        return envelope("snapshot", toSnapshotJson(snapshot));
    }

    public ObjectNode listRecoveryPoints() {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("recoveryPoints", objectMapper.createArrayNode());
        return response;
    }

    public ObjectNode listTableRestoreStatus() {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("tableRestoreStatuses", objectMapper.createArrayNode());
        return response;
    }

    public ObjectNode listTagsForResource(JsonNode request) {
        Map<String, String> tags = tagged(requireText(request, "resourceArn")).tags;
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("tags");
        tags.forEach((key, value) -> {
            ObjectNode tag = list.addObject();
            tag.put("key", key);
            tag.put("value", value);
        });
        return response;
    }

    public ObjectNode tagResource(JsonNode request) {
        Map<String, String> tags = tagged(requireText(request, "resourceArn")).tags;
        applyTags(tags, request.get("tags"));
        return objectMapper.createObjectNode();
    }

    public ObjectNode untagResource(JsonNode request) {
        Map<String, String> tags = tagged(requireText(request, "resourceArn")).tags;
        JsonNode keys = request.get("tagKeys");
        if (keys != null && keys.isArray()) {
            for (JsonNode key : keys) {
                tags.remove(key.asText());
            }
        }
        return objectMapper.createObjectNode();
    }

    private ObjectNode toNamespaceJson(Namespace namespace) {
        ObjectNode json = objectMapper.createObjectNode();
        json.put("namespaceName", namespace.namespaceName);
        json.put("namespaceId", namespace.namespaceId);
        json.put("namespaceArn", namespace.namespaceArn);
        json.put("dbName", namespace.dbName);
        json.put("adminUsername", namespace.adminUsername);
        json.put("status", namespace.status);
        json.put("creationDate", namespace.creationDate);
        if (namespace.kmsKeyId != null) {
            json.put("kmsKeyId", namespace.kmsKeyId);
        }
        if (namespace.defaultIamRoleArn != null) {
            json.put("defaultIamRoleArn", namespace.defaultIamRoleArn);
        }
        if (namespace.adminPasswordSecretArn != null) {
            json.put("adminPasswordSecretArn", namespace.adminPasswordSecretArn);
        }
        if (namespace.adminPasswordSecretKmsKeyId != null) {
            json.put("adminPasswordSecretKmsKeyId", namespace.adminPasswordSecretKmsKeyId);
        }
        ArrayNode roles = json.putArray("iamRoles");
        namespace.iamRoles.forEach(roles::add);
        ArrayNode exports = json.putArray("logExports");
        namespace.logExports.forEach(exports::add);
        return json;
    }

    private ObjectNode toWorkgroupJson(Workgroup workgroup) {
        ObjectNode json = objectMapper.createObjectNode();
        json.put("workgroupName", workgroup.workgroupName);
        json.put("workgroupId", workgroup.workgroupId);
        json.put("workgroupArn", workgroup.workgroupArn);
        json.put("namespaceName", workgroup.namespaceName);
        json.put("baseCapacity", workgroup.baseCapacity);
        json.put("enhancedVpcRouting", workgroup.enhancedVpcRouting);
        json.put("publiclyAccessible", workgroup.publiclyAccessible);
        json.put("port", workgroup.port);
        json.put("status", workgroup.status);
        json.put("creationDate", workgroup.creationDate);
        if (workgroup.maxCapacity != null) {
            json.put("maxCapacity", workgroup.maxCapacity);
        }
        ArrayNode subnets = json.putArray("subnetIds");
        workgroup.subnetIds.forEach(subnets::add);
        ArrayNode groups = json.putArray("securityGroupIds");
        workgroup.securityGroupIds.forEach(groups::add);
        ArrayNode config = json.putArray("configParameters");
        for (Map<String, String> parameter : workgroup.configParameters) {
            ObjectNode node = config.addObject();
            node.put("parameterKey", parameter.get("parameterKey"));
            node.put("parameterValue", parameter.get("parameterValue"));
        }
        ObjectNode endpoint = json.putObject("endpoint");
        endpoint.put("address", workgroup.endpointAddress);
        endpoint.put("port", workgroup.port);
        endpoint.set("vpcEndpoints", objectMapper.createArrayNode());
        return json;
    }

    private ObjectNode toSnapshotJson(Snapshot snapshot) {
        ObjectNode json = objectMapper.createObjectNode();
        json.put("snapshotName", snapshot.snapshotName);
        json.put("snapshotArn", snapshot.snapshotArn);
        json.put("namespaceName", snapshot.namespaceName);
        json.put("namespaceArn", snapshot.namespaceArn);
        json.put("status", snapshot.status);
        json.put("ownerAccount", snapshot.ownerAccount);
        json.put("snapshotCreateTime", snapshot.snapshotCreateTime);
        json.put("snapshotRetentionPeriod", snapshot.snapshotRetentionPeriod);
        json.put("snapshotRemainingDays", snapshot.snapshotRemainingDays);
        json.put("snapshotRetentionStartTime", snapshot.snapshotRetentionStartTime);
        json.put("adminUsername", snapshot.adminUsername);
        if (snapshot.kmsKeyId != null) {
            json.put("kmsKeyId", snapshot.kmsKeyId);
        }
        if (snapshot.adminPasswordSecretArn != null) {
            json.put("adminPasswordSecretArn", snapshot.adminPasswordSecretArn);
        }
        if (snapshot.adminPasswordSecretKmsKeyId != null) {
            json.put("adminPasswordSecretKmsKeyId", snapshot.adminPasswordSecretKmsKeyId);
        }
        json.put("totalBackupSizeInMegaBytes", 1);
        json.put("actualIncrementalBackupSizeInMegaBytes", 1);
        json.put("backupProgressInMegaBytes", 1);
        json.put("currentBackupRateInMegaBytesPerSecond", 1);
        json.put("estimatedSecondsToCompletion", 0);
        json.put("elapsedTimeInSeconds", 0);
        return json;
    }

    private Snapshot resolveSnapshot(JsonNode request) {
        String snapshotName = textOrNull(request, "snapshotName");
        if (snapshotName != null) {
            return requireSnapshot(snapshotName);
        }
        String snapshotArn = textOrNull(request, "snapshotArn");
        if (snapshotArn != null) {
            for (Snapshot snapshot : snapshots.values()) {
                if (snapshotArn.equals(snapshot.snapshotArn)) {
                    return snapshot;
                }
            }
            throw notFound("Snapshot " + snapshotArn + " was not found.");
        }
        throw validation("snapshotName or snapshotArn is required.");
    }

    private String putAdminSecret(String namespaceName, String username, String password, String region) {
        String secretName = "redshift!" + namespaceName;
        String secretString = "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
        try {
            return secretsManagerService.createSecret(
                    secretName, secretString, null,
                    "Redshift Serverless admin password for " + namespaceName,
                    null, List.of(), region).getArn();
        } catch (AwsException e) {
            if (!"ResourceExistsException".equals(e.getErrorCode())) {
                throw e;
            }
            return regionResolver.buildArn("secretsmanager", region, "secret:" + secretName);
        }
    }

    private String arn(String region, String resource) {
        return regionResolver.buildArn("redshift-serverless", region, resource);
    }

    private Tagged tagged(String resourceArn) {
        for (Namespace namespace : namespaces.values()) {
            if (resourceArn.equals(namespace.namespaceArn)) {
                return new Tagged(namespace.tags);
            }
        }
        for (Workgroup workgroup : workgroups.values()) {
            if (resourceArn.equals(workgroup.workgroupArn)) {
                return new Tagged(workgroup.tags);
            }
        }
        for (Snapshot snapshot : snapshots.values()) {
            if (resourceArn.equals(snapshot.snapshotArn)) {
                return new Tagged(snapshot.tags);
            }
        }
        throw notFound("Resource " + resourceArn + " was not found.");
    }

    private record Tagged(Map<String, String> tags) {}

    private ObjectNode envelope(String field, ObjectNode value) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set(field, value);
        return response;
    }

    private static void applyTags(Map<String, String> target, JsonNode tags) {
        if (tags == null || !tags.isArray()) {
            return;
        }
        for (JsonNode tag : tags) {
            String key = textOrNull(tag, "key");
            String value = textOrNull(tag, "value");
            if (key != null && value != null) {
                target.put(key, value);
            }
        }
    }

    private static List<String> stringList(JsonNode request, String field) {
        JsonNode node = request.get(field);
        List<String> values = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(item -> values.add(item.asText()));
        }
        return values;
    }

    private static List<Map<String, String>> configParameters(JsonNode node) {
        List<Map<String, String>> parameters = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return parameters;
        }
        for (JsonNode item : node) {
            String key = textOrNull(item, "parameterKey");
            String value = textOrNull(item, "parameterValue");
            if (key != null) {
                parameters.add(Map.of("parameterKey", key, "parameterValue", value == null ? "" : value));
            }
        }
        return parameters;
    }

    private static String generatePassword() {
        StringBuilder builder = new StringBuilder("A1a!");
        for (int i = 0; i < 20; i++) {
            builder.append(PASSWORD_ALPHABET.charAt(RANDOM.nextInt(PASSWORD_ALPHABET.length())));
        }
        return builder.toString();
    }

    private static String requireText(JsonNode request, String field) {
        String value = textOrNull(request, field);
        if (value == null) {
            throw validation(field + " is required.");
        }
        return value;
    }

    private static String textOrDefault(JsonNode request, String field, String fallback) {
        String value = textOrNull(request, field);
        return value == null ? fallback : value;
    }

    private static String textOrNull(JsonNode request, String field) {
        JsonNode node = request.get(field);
        if (node == null || node.isNull() || node.asText().isBlank()) {
            return null;
        }
        return node.asText();
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
