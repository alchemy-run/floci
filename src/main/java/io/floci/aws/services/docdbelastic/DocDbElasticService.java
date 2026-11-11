package io.floci.aws.services.docdbelastic;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.Pagination;
import io.floci.aws.core.common.dns.EmbeddedDnsServer;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.ec2.model.NetworkInterface;
import io.floci.aws.services.ec2.model.SecurityGroup;
import io.floci.aws.services.ec2.model.Subnet;
import io.floci.aws.services.ec2.model.Vpc;
import io.floci.aws.services.kms.KmsService;
import io.floci.aws.services.kms.model.KmsKey;
import io.floci.aws.services.secretsmanager.SecretsManagerService;
import io.floci.aws.services.secretsmanager.model.SecretVersion;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Amazon DocumentDB elastic clusters: clusters served by a MongoDB container on their endpoint, their shards and
 * network interfaces, manual and automated snapshots that carry the cluster's data, stop and start, restores and
 * copies, pending maintenance and tags.
 *
 * <p>Records live per account under {@code <region>/<kind>/<uuid>}; a record's {@code _} member holds what no
 * response shows (the admin password the cluster's MongoDB needs, the container, network interfaces, tags and the
 * status a transition settles in).
 */
@ApplicationScoped
public class DocDbElasticService {

    private static final Logger LOG = Logger.getLogger(DocDbElasticService.class);
    static final String SERVICE = "docdb-elastic";
    private static final String CLUSTER = "cluster";
    private static final String SNAPSHOT = "cluster-snapshot";
    private static final String DEFAULT_KMS_KEY = "AWS_OWNED_KMS_KEY";
    private static final Set<Integer> SHARD_CAPACITIES = Set.of(2, 4, 8, 16, 32, 64);
    private static final Pattern NAME = Pattern.compile("[a-zA-Z](?:[a-zA-Z0-9]|-(?!-))*");
    private static final Pattern ADMIN = Pattern.compile("[a-zA-Z][a-zA-Z0-9]{0,62}");
    private static final Pattern BACKUP_WINDOW = Pattern.compile(
            "([01]\\d|2[0-3]):([0-5]\\d)-([01]\\d|2[0-3]):([0-5]\\d)");
    private static final Pattern MAINTENANCE_WINDOW = Pattern.compile(
            "(mon|tue|wed|thu|fri|sat|sun):([01]\\d|2[0-3]):([0-5]\\d)-(mon|tue|wed|thu|fri|sat|sun):([01]\\d|2[0-3]):"
                    + "([0-5]\\d)");
    private static final List<String> DAYS = List.of("mon", "tue", "wed", "thu", "fri", "sat", "sun");
    private static final Pattern RESOURCE_ARN = Pattern.compile("arn:" + AwsArnUtils.PARTITION_REGEX
            + ":docdb-elastic:([a-z0-9-]+):(\\d{12}):(cluster|cluster-snapshot)/([0-9a-f-]{36})");
    private static final DateTimeFormatter AUTOMATED = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm")
            .withZone(ZoneOffset.UTC);

    private final AccountAwareStorageBackend<ObjectNode> storage;
    private final ObjectMapper mapper;
    private final EmulatorConfig config;
    private final Ec2Service ec2;
    private final KmsService kms;
    private final SecretsManagerService secrets;
    private final DocDbElasticBackend backend;
    private final SecureRandom random = new SecureRandom();

    @Inject
    public DocDbElasticService(StorageFactory storageFactory, ObjectMapper mapper, EmulatorConfig config,
                               Ec2Service ec2, KmsService kms, SecretsManagerService secrets,
                               DocDbElasticBackend backend) {
        this.storage = storageFactory.create("docdb-elastic", "resources.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.mapper = mapper;
        this.config = config;
        this.ec2 = ec2;
        this.kms = kms;
        this.secrets = secrets;
        this.backend = backend;
    }

    // ---------------------------------------------------------------- clusters

    public synchronized ObjectNode createCluster(ObjectNode input, String region) {
        String name = name(input, "clusterName");
        String token = optional(input, "clientToken");
        Optional<ObjectNode> existing = clusterNamed(region, name);
        if (existing.isPresent()) {
            if (token != null && token.equals(meta(existing.get()).path("clientToken").asText(null))) {
                return wrap("cluster", existing.get());
            }
            throw conflict("Cluster " + name + " already exists.", name, CLUSTER);
        }
        String admin = text(input, "adminUserName");
        if (!ADMIN.matcher(admin).matches() || "serviceadmin".equalsIgnoreCase(admin)) {
            throw invalid("adminUserName must begin with a letter, contain only letters and numbers, be 1 to 63 "
                    + "characters long and not be a reserved word.");
        }
        String authType = text(input, "authType");
        Spec spec = new Spec();
        spec.name = name;
        spec.admin = admin;
        spec.authType = authType;
        spec.passwordInput = text(input, "adminUserPassword");
        spec.password = password(authType, spec.passwordInput, region);
        spec.shardCapacity = shardCapacity(input.path("shardCapacity").asInt());
        spec.shardCount = range(input, "shardCount", 1, 32, -1);
        spec.shardInstanceCount = range(input, "shardInstanceCount", 1, 16, 2);
        spec.backupRetention = range(input, "backupRetentionPeriod", 1, 35, 1);
        spec.backupWindow = backupWindow(optional(input, "preferredBackupWindow"));
        spec.maintenanceWindow = maintenanceWindow(optional(input, "preferredMaintenanceWindow"));
        spec.kmsKeyId = kmsKey(optional(input, "kmsKeyId"), region);
        spec.subnetIds = strings(input.get("subnetIds"));
        spec.securityGroupIds = strings(input.get("vpcSecurityGroupIds"));
        spec.clientToken = token;
        spec.tags = tags(input.get("tags"), Map.of());
        return wrap("cluster", provision(region, spec, null));
    }

    public synchronized ObjectNode getCluster(ObjectNode input, String region) {
        return wrap("cluster", cluster(region, text(input, "clusterArn")));
    }

    public synchronized ObjectNode listClusters(ObjectNode input, String region) {
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode cluster : all(region, CLUSTER)) {
            ObjectNode item = mapper.createObjectNode();
            item.put("clusterArn", cluster.path("clusterArn").asText())
                    .put("clusterName", cluster.path("clusterName").asText())
                    .put("status", cluster.path("status").asText());
            items.add(item);
        }
        return page(items, input, "clusters", 100);
    }

    public synchronized ObjectNode updateCluster(ObjectNode input, String region) {
        String arn = text(input, "clusterArn");
        ObjectNode cluster = cluster(region, arn);
        requireActive(cluster, true);
        ObjectNode meta = meta(cluster);
        String settled = "UPDATING";
        if (input.hasNonNull("shardCapacity")) {
            cluster.put("shardCapacity", shardCapacity(input.get("shardCapacity").asInt()));
        }
        if (input.hasNonNull("shardInstanceCount")) {
            cluster.put("shardInstanceCount", range(input, "shardInstanceCount", 1, 16, 2));
        }
        if (input.hasNonNull("shardCount")) {
            int count = range(input, "shardCount", 1, 32, 1);
            int current = cluster.path("shardCount").asInt();
            if (count != current) {
                settled = count > current ? "SPLITTING" : "MERGING";
                cluster.put("shardCount", count);
                resizeShards(cluster, count);
            }
        }
        if (input.hasNonNull("backupRetentionPeriod")) {
            cluster.put("backupRetentionPeriod", range(input, "backupRetentionPeriod", 1, 35, 1));
        }
        if (input.hasNonNull("preferredBackupWindow")) {
            cluster.put("preferredBackupWindow", backupWindow(input.get("preferredBackupWindow").asText()));
        }
        if (input.hasNonNull("preferredMaintenanceWindow")) {
            cluster.put("preferredMaintenanceWindow",
                    maintenanceWindow(input.get("preferredMaintenanceWindow").asText()));
        }
        if (input.has("subnetIds") || input.has("vpcSecurityGroupIds")) {
            List<String> subnets = input.has("subnetIds") ? strings(input.get("subnetIds"))
                    : strings(cluster.get("subnetIds"));
            List<String> groups = input.has("vpcSecurityGroupIds") ? strings(input.get("vpcSecurityGroupIds"))
                    : strings(cluster.get("vpcSecurityGroupIds"));
            Network network = network(region, subnets, groups);
            releaseInterfaces(region, cluster);
            place(region, cluster, network);
        }
        String authType = optional(input, "authType");
        String passwordInput = optional(input, "adminUserPassword");
        if (authType != null && !authType.equals(cluster.path("authType").asText()) && passwordInput == null) {
            throw invalid("adminUserPassword is required when authType changes.");
        }
        if (passwordInput != null) {
            String type = authType != null ? authType : cluster.path("authType").asText();
            String password = password(type, passwordInput, region);
            backend.changePassword(cluster, meta.path("password").asText(), password);
            meta.put("password", password).put("passwordInput", passwordInput);
            cluster.put("authType", type);
        }
        transition(region, cluster, settled, "ACTIVE");
        return wrap("cluster", view(cluster));
    }

    public synchronized ObjectNode deleteCluster(ObjectNode input, String region) {
        ObjectNode cluster = cluster(region, text(input, "clusterArn"));
        String status = cluster.path("status").asText();
        if (Set.of("STOPPED", "STOPPING", "STARTING").contains(status)) {
            throw invalid("Can't delete the cluster because it is in " + status + " state.", "other");
        }
        if (!"ACTIVE".equals(status)) {
            throw conflict("Cluster " + cluster.path("clusterName").asText() + " is in " + status
                    + " state and cannot be deleted.", cluster.path("clusterArn").asText(), CLUSTER);
        }
        backend.stop(cluster);
        releaseInterfaces(region, cluster);
        backend.deleteArchive("stopped", uuid(cluster.path("clusterArn").asText()));
        for (ObjectNode snapshot : all(region, SNAPSHOT)) {
            if ("AUTOMATED".equals(snapshot.path("snapshotType").asText())
                    && cluster.path("clusterArn").asText().equals(snapshot.path("clusterArn").asText())) {
                String id = uuid(snapshot.path("snapshotArn").asText());
                backend.deleteArchive("snapshots", id);
                storage.deleteForAccount(account(), key(region, SNAPSHOT, id));
            }
        }
        ObjectNode response = wrap("cluster", view(transition(region, cluster, "DELETING", null)));
        response.withObject("/cluster").put("status", "DELETING");
        return response;
    }

    public synchronized ObjectNode stopCluster(ObjectNode input, String region) {
        ObjectNode cluster = cluster(region, text(input, "clusterArn"));
        String status = cluster.path("status").asText();
        if (!"ACTIVE".equals(status)) {
            throw invalid("Cluster " + cluster.path("clusterName").asText() + " is in " + status
                    + " state; only an ACTIVE cluster can be stopped.", "other");
        }
        for (ObjectNode snapshot : all(region, SNAPSHOT)) {
            if ("CREATING".equals(snapshot.path("status").asText())
                    && cluster.path("clusterArn").asText().equals(snapshot.path("clusterArn").asText())) {
                throw invalid("Cluster " + cluster.path("clusterName").asText()
                        + " cannot be stopped while a snapshot of it is being created.", "other");
            }
        }
        String id = uuid(cluster.path("clusterArn").asText());
        backend.deleteArchive("stopped", id);
        backend.dump(cluster, meta(cluster).path("password").asText(), backend.archive("stopped", id));
        backend.stop(cluster);
        return wrap("cluster", view(transition(region, cluster, "STOPPING", "STOPPED")));
    }

    public synchronized ObjectNode startCluster(ObjectNode input, String region) {
        ObjectNode cluster = cluster(region, text(input, "clusterArn"));
        String status = cluster.path("status").asText();
        if (!"STOPPED".equals(status)) {
            throw invalid("Cluster " + cluster.path("clusterName").asText() + " is in " + status
                    + " state; only a STOPPED cluster can be started.", "other");
        }
        String password = meta(cluster).path("password").asText();
        String id = uuid(cluster.path("clusterArn").asText());
        backend.start(cluster, password);
        backend.restore(cluster, password, backend.archive("stopped", id));
        backend.deleteArchive("stopped", id);
        return wrap("cluster", view(transition(region, cluster, "STARTING", "ACTIVE")));
    }

    // ---------------------------------------------------------------- snapshots

    public synchronized ObjectNode createClusterSnapshot(ObjectNode input, String region) {
        ObjectNode cluster = cluster(region, text(input, "clusterArn"));
        String status = cluster.path("status").asText();
        if (!"ACTIVE".equals(status)) {
            throw invalid("Cluster " + cluster.path("clusterName").asText() + " is in " + status
                    + " state; a snapshot can only be taken of an ACTIVE cluster.", "other");
        }
        String name = name(input, "snapshotName");
        requireFreeSnapshotName(region, name);
        ObjectNode snapshot = snapshotOf(region, cluster, name, "MANUAL", cluster.path("kmsKeyId").asText());
        String id = uuid(snapshot.path("snapshotArn").asText());
        backend.dump(cluster, meta(cluster).path("password").asText(), backend.archive("snapshots", id));
        meta(snapshot).set("tags", tags(input.get("tags"), Map.of()));
        return wrap("snapshot", view(transition(region, snapshot, "CREATING", "ACTIVE")));
    }

    public synchronized ObjectNode getClusterSnapshot(ObjectNode input, String region) {
        return wrap("snapshot", snapshot(region, text(input, "snapshotArn")));
    }

    public synchronized ObjectNode listClusterSnapshots(ObjectNode input, String region) {
        String clusterArn = optional(input, "clusterArn");
        String type = optional(input, "snapshotType");
        if (type != null && !Set.of("MANUAL", "AUTOMATED").contains(type.toUpperCase(Locale.ROOT))) {
            throw invalid("snapshotType must be one of MANUAL or AUTOMATED.");
        }
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode snapshot : all(region, SNAPSHOT)) {
            if (clusterArn != null && !clusterArn.equals(snapshot.path("clusterArn").asText())) {
                continue;
            }
            if (type != null && !type.equalsIgnoreCase(snapshot.path("snapshotType").asText())) {
                continue;
            }
            ObjectNode item = mapper.createObjectNode();
            item.put("clusterArn", snapshot.path("clusterArn").asText())
                    .put("snapshotArn", snapshot.path("snapshotArn").asText())
                    .put("snapshotCreationTime", snapshot.path("snapshotCreationTime").asText())
                    .put("snapshotName", snapshot.path("snapshotName").asText())
                    .put("status", snapshot.path("status").asText());
            items.add(item);
        }
        return page(items, input, "snapshots", 100);
    }

    public synchronized ObjectNode deleteClusterSnapshot(ObjectNode input, String region) {
        String arn = text(input, "snapshotArn");
        ObjectNode snapshot = snapshotRecord(region, arn);
        if ("AUTOMATED".equals(snapshot.path("snapshotType").asText())) {
            throw invalid("Automated snapshots are deleted with their cluster and cannot be deleted directly.",
                    "other");
        }
        String status = snapshot.path("status").asText();
        if (!"ACTIVE".equals(status)) {
            throw conflict("Snapshot " + snapshot.path("snapshotName").asText() + " is in " + status
                    + " state and cannot be deleted.", arn, SNAPSHOT);
        }
        backend.deleteArchive("snapshots", uuid(arn));
        ObjectNode response = wrap("snapshot", view(transition(region, snapshot, "DELETING", null)));
        response.withObject("/snapshot").put("status", "DELETING");
        return response;
    }

    public synchronized ObjectNode copyClusterSnapshot(ObjectNode input, String region) {
        String sourceArn = text(input, "snapshotArn");
        ObjectNode source = sourceSnapshot(region, sourceArn, "CopyClusterSnapshot");
        String name = name(input, "targetSnapshotName");
        requireFreeSnapshotName(region, name);
        String kmsKeyId = input.hasNonNull("kmsKeyId") ? kmsKey(input.get("kmsKeyId").asText(), region)
                : source.path("kmsKeyId").asText();
        ObjectNode copy = snapshotOf(region, source, name, "MANUAL", kmsKeyId);
        ObjectNode sourceMeta = meta(source);
        for (String field : List.of("password", "passwordInput", "authType", "shardCapacity", "shardCount",
                "shardInstanceCount", "backupRetentionPeriod", "preferredBackupWindow",
                "preferredMaintenanceWindow")) {
            if (sourceMeta.has(field)) {
                meta(copy).set(field, sourceMeta.get(field));
            }
        }
        Map<String, String> tags = input.path("copyTags").asBoolean(false) ? tagMap(sourceMeta.path("tags"))
                : Map.of();
        meta(copy).set("tags", tags(input.get("tags"), tags));
        storage.putForAccount(account(), key(region, SNAPSHOT, uuid(copy.path("snapshotArn").asText())), copy);
        backend.copyArchive("snapshots", uuid(sourceArn), uuid(copy.path("snapshotArn").asText()));
        return wrap("snapshot", view(transition(region, copy, "CREATING", "ACTIVE")));
    }

    public synchronized ObjectNode restoreClusterFromSnapshot(ObjectNode input, String region) {
        String sourceArn = text(input, "snapshotArn");
        ObjectNode source = sourceSnapshot(region, sourceArn, "RestoreClusterFromSnapshot");
        String name = name(input, "clusterName");
        if (clusterNamed(region, name).isPresent()) {
            throw conflict("Cluster " + name + " already exists.", name, CLUSTER);
        }
        ObjectNode sourceMeta = meta(source);
        Spec spec = new Spec();
        spec.name = name;
        spec.admin = source.path("adminUserName").asText();
        spec.authType = sourceMeta.path("authType").asText("PLAIN_TEXT");
        spec.password = sourceMeta.path("password").asText();
        spec.passwordInput = sourceMeta.path("passwordInput").asText(spec.password);
        spec.shardCapacity = input.hasNonNull("shardCapacity") ? shardCapacity(input.get("shardCapacity").asInt())
                : sourceMeta.path("shardCapacity").asInt(2);
        spec.shardCount = sourceMeta.path("shardCount").asInt(1);
        spec.shardInstanceCount = input.hasNonNull("shardInstanceCount")
                ? range(input, "shardInstanceCount", 1, 16, 2) : sourceMeta.path("shardInstanceCount").asInt(2);
        spec.backupRetention = sourceMeta.path("backupRetentionPeriod").asInt(1);
        spec.backupWindow = sourceMeta.path("preferredBackupWindow").asText(backupWindow(null));
        spec.maintenanceWindow = maintenanceWindow(null);
        spec.kmsKeyId = input.hasNonNull("kmsKeyId") ? kmsKey(input.get("kmsKeyId").asText(), region)
                : source.path("kmsKeyId").asText();
        spec.subnetIds = input.has("subnetIds") ? strings(input.get("subnetIds"))
                : strings(source.get("subnetIds"));
        spec.securityGroupIds = input.has("vpcSecurityGroupIds") ? strings(input.get("vpcSecurityGroupIds"))
                : strings(source.get("vpcSecurityGroupIds"));
        spec.tags = tags(input.get("tags"), Map.of());
        return wrap("cluster", provision(region, spec, backend.archive("snapshots", uuid(sourceArn))));
    }

    // ---------------------------------------------------------------- maintenance

    public synchronized ObjectNode getPendingMaintenanceAction(ObjectNode input, String region) {
        ObjectNode cluster = cluster(region, text(input, "resourceArn"));
        ObjectNode response = mapper.createObjectNode();
        response.set("resourcePendingMaintenanceAction", pending(cluster));
        return response;
    }

    public synchronized ObjectNode listPendingMaintenanceActions(ObjectNode input, String region) {
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode cluster : all(region, CLUSTER)) {
            if (!meta(cluster).path("pending").isEmpty()) {
                items.add(pending(cluster));
            }
        }
        return page(items, input, "resourcePendingMaintenanceActions", 100);
    }

    /**
     * Opts in to, schedules or withdraws from a maintenance action pending on a cluster; an action applied
     * immediately puts the cluster in MAINTENANCE until it is done.
     */
    public synchronized ObjectNode applyPendingMaintenanceAction(ObjectNode input, String region) {
        String arn = text(input, "resourceArn");
        ObjectNode cluster = cluster(region, arn);
        String action = text(input, "applyAction");
        String optIn = text(input, "optInType");
        ArrayNode pending = meta(cluster).withArray("pending");
        ObjectNode details = null;
        for (JsonNode item : pending) {
            if (action.equals(item.path("action").asText())) {
                details = (ObjectNode) item;
            }
        }
        if (details == null) {
            throw invalid("Maintenance action " + action + " is not pending for resource " + arn + ".", "other");
        }
        switch (optIn) {
            case "IMMEDIATE" -> {
                requireActive(cluster, false);
                for (Iterator<JsonNode> items = pending.elements(); items.hasNext(); ) {
                    if (action.equals(items.next().path("action").asText())) {
                        items.remove();
                    }
                }
                transition(region, cluster, "MAINTENANCE", "ACTIVE");
                details.put("optInStatus", "immediate");
            }
            case "NEXT_MAINTENANCE" -> details.put("optInStatus", "next-maintenance");
            case "APPLY_ON" -> {
                String applyOn = text(input, "applyOn");
                try {
                    Instant.parse(applyOn);
                } catch (DateTimeParseException e) {
                    throw invalid("applyOn must be an ISO-8601 timestamp.");
                }
                details.put("optInStatus", "apply-on").put("currentApplyDate", applyOn);
            }
            default -> {
                details.remove("optInStatus");
                details.remove("currentApplyDate");
            }
        }
        storage.putForAccount(account(), key(region, CLUSTER, uuid(arn)), cluster);
        ObjectNode response = mapper.createObjectNode();
        ObjectNode result = response.putObject("resourcePendingMaintenanceAction").put("resourceArn", arn);
        result.putArray("pendingMaintenanceActionDetails").add(details);
        return response;
    }

    // ---------------------------------------------------------------- tags

    public synchronized ObjectNode listTagsForResource(ObjectNode input, String region) {
        ObjectNode record = taggable(region, text(input, "resourceArn"));
        ObjectNode response = mapper.createObjectNode();
        response.set("tags", meta(record).path("tags").deepCopy());
        return response;
    }

    public synchronized ObjectNode tagResource(ObjectNode input, String region) {
        String arn = text(input, "resourceArn");
        ObjectNode record = taggable(region, arn);
        meta(record).set("tags", tags(input.get("tags"), tagMap(meta(record).path("tags"))));
        save(region, record);
        return mapper.createObjectNode();
    }

    public synchronized ObjectNode untagResource(ObjectNode input, String region) {
        String arn = text(input, "resourceArn");
        ObjectNode record = taggable(region, arn);
        ObjectNode tags = meta(record).withObject("/tags");
        for (String key : strings(input.get("tagKeys"))) {
            tags.remove(key);
        }
        save(region, record);
        return mapper.createObjectNode();
    }

    // ---------------------------------------------------------------- provisioning

    /** What a new cluster is made of, from CreateCluster or from a snapshot. */
    private static final class Spec {
        String name;
        String admin;
        String authType;
        String password;
        String passwordInput;
        int shardCapacity;
        int shardCount;
        int shardInstanceCount;
        int backupRetention;
        String backupWindow;
        String maintenanceWindow;
        String kmsKeyId;
        List<String> subnetIds;
        List<String> securityGroupIds;
        String clientToken;
        ObjectNode tags;
    }

    private record Network(String vpcId, List<Subnet> subnets, List<String> groupIds) {}

    /**
     * Creates a cluster: one network interface per subnet, a MongoDB serving its endpoint, loaded from a snapshot's
     * dump when there is one, then CREATING until it settles ACTIVE.
     */
    private ObjectNode provision(String region, Spec spec, Path dump) {
        Network network = network(region, spec.subnetIds, spec.securityGroupIds);
        String id = UUID.randomUUID().toString();
        String account = account();
        ObjectNode cluster = mapper.createObjectNode();
        cluster.put("adminUserName", spec.admin).put("authType", spec.authType)
                .put("backupRetentionPeriod", spec.backupRetention)
                .put("clusterArn", AwsArnUtils.Arn.of(SERVICE, region, account, "cluster/" + id).toString())
                .put("clusterEndpoint", spec.name + "-" + account + "." + region + ".docdb-elastic." + dnsSuffix())
                .put("clusterName", spec.name).put("createTime", now()).put("kmsKeyId", spec.kmsKeyId)
                .put("preferredBackupWindow", spec.backupWindow)
                .put("preferredMaintenanceWindow", spec.maintenanceWindow)
                .put("shardCapacity", spec.shardCapacity).put("shardCount", spec.shardCount)
                .put("shardInstanceCount", spec.shardInstanceCount).put("status", "CREATING");
        cluster.putArray("shards");
        ObjectNode meta = cluster.putObject("_");
        meta.put("seq", System.nanoTime()).put("password", spec.password).put("passwordInput", spec.passwordInput);
        if (spec.clientToken != null) {
            meta.put("clientToken", spec.clientToken);
        }
        meta.set("tags", spec.tags);
        meta.putArray("pending");
        resizeShards(cluster, spec.shardCount);
        place(region, cluster, network);
        try {
            backend.start(cluster, spec.password);
            if (dump != null) {
                backend.restore(cluster, spec.password, dump);
            }
        } catch (RuntimeException e) {
            backend.stop(cluster);
            releaseInterfaces(region, cluster);
            throw e;
        }
        LOG.infov("DocumentDB elastic cluster {0} created in {1}", spec.name, region);
        return view(transition(region, cluster, "CREATING", "ACTIVE"));
    }

    /**
     * The subnets and security groups a cluster is placed in. Without subnets, the default VPC's default subnets
     * in up to three Availability Zones; without security groups, the VPC's default group.
     */
    private Network network(String region, List<String> subnetIds, List<String> groupIds) {
        ec2.ensureDefaultResources(region);
        List<Subnet> subnets;
        if (subnetIds.isEmpty()) {
            List<Vpc> vpcs = ec2.describeVpcs(region, List.of(), Map.of("is-default", List.of("true")));
            if (vpcs.isEmpty()) {
                throw invalid("No default VPC exists in " + region + "; specify subnetIds.");
            }
            subnets = new ArrayList<>(ec2.describeSubnets(region, List.of(), Map.of("vpc-id",
                    List.of(vpcs.get(0).getVpcId()), "default-for-az", List.of("true"))));
            subnets.sort(Comparator.comparing(Subnet::getAvailabilityZone));
            subnets = new ArrayList<>(subnets.subList(0, Math.min(3, subnets.size())));
            if (subnets.isEmpty()) {
                throw invalid("The default VPC in " + region + " has no default subnets; specify subnetIds.");
            }
        } else {
            Set<String> distinct = new LinkedHashSet<>(subnetIds);
            try {
                subnets = new ArrayList<>(ec2.describeSubnets(region, List.copyOf(distinct), Map.of()));
            } catch (AwsException e) {
                throw invalid("Invalid subnet ID(s): " + String.join(", ", subnetIds) + ".");
            }
            if (subnets.size() != distinct.size()) {
                throw invalid("Invalid subnet ID(s): " + String.join(", ", subnetIds) + ".");
            }
        }
        String vpcId = subnets.get(0).getVpcId();
        for (Subnet subnet : subnets) {
            if (!vpcId.equals(subnet.getVpcId())) {
                throw invalid("All subnets must belong to the same VPC.");
            }
        }
        List<String> groups;
        if (groupIds.isEmpty()) {
            groups = new ArrayList<>();
            for (SecurityGroup group : ec2.describeSecurityGroups(region, List.of(), List.of(),
                    Map.of("vpc-id", List.of(vpcId), "group-name", List.of("default")))) {
                groups.add(group.getGroupId());
            }
        } else {
            groups = List.copyOf(new LinkedHashSet<>(groupIds));
            List<SecurityGroup> found;
            try {
                found = ec2.describeSecurityGroups(region, groups, List.of(), Map.of());
            } catch (AwsException e) {
                throw invalid("Invalid security group ID(s): " + String.join(", ", groupIds) + ".");
            }
            if (found.size() != groups.size()) {
                throw invalid("Invalid security group ID(s): " + String.join(", ", groupIds) + ".");
            }
            for (SecurityGroup group : found) {
                if (!vpcId.equals(group.getVpcId())) {
                    throw invalid("Security group " + group.getGroupId() + " is not in VPC " + vpcId + ".");
                }
            }
        }
        return new Network(vpcId, subnets, groups);
    }

    /** Places the cluster's endpoint in its subnets: one network interface in each. */
    private void place(String region, ObjectNode cluster, Network network) {
        ArrayNode subnets = cluster.putArray("subnetIds");
        ArrayNode groups = cluster.putArray("vpcSecurityGroupIds");
        network.groupIds().forEach(groups::add);
        ArrayNode interfaces = meta(cluster).putArray("networkInterfaces");
        for (Subnet subnet : network.subnets()) {
            subnets.add(subnet.getSubnetId());
            NetworkInterface eni = ec2.createNetworkInterface(region, subnet.getSubnetId(),
                    "DocumentDB elastic cluster " + cluster.path("clusterName").asText(), null, null,
                    network.groupIds(), null);
            interfaces.add(eni.getNetworkInterfaceId());
        }
    }

    private void releaseInterfaces(String region, ObjectNode cluster) {
        for (JsonNode eni : meta(cluster).path("networkInterfaces")) {
            try {
                ec2.deleteNetworkInterface(region, eni.asText());
            } catch (AwsException e) {
                LOG.debugv("Could not delete {0}: {1}", eni.asText(), e.getMessage());
            }
        }
        meta(cluster).putArray("networkInterfaces");
    }

    private void resizeShards(ObjectNode cluster, int count) {
        ArrayNode shards = cluster.withArray("shards");
        while (shards.size() > count) {
            shards.remove(shards.size() - 1);
        }
        while (shards.size() < count) {
            shards.addObject().put("createTime", now()).put("shardId", shardId())
                    .put("status", cluster.path("status").asText());
        }
    }

    private ObjectNode snapshotOf(String region, ObjectNode from, String name, String type, String kmsKeyId) {
        String id = UUID.randomUUID().toString();
        ObjectNode snapshot = mapper.createObjectNode();
        boolean fromCluster = from.has("clusterName");
        snapshot.put("adminUserName", from.path("adminUserName").asText())
                .put("clusterArn", from.path("clusterArn").asText())
                .put("clusterCreationTime", fromCluster ? from.path("createTime").asText()
                        : from.path("clusterCreationTime").asText())
                .put("kmsKeyId", kmsKeyId)
                .put("snapshotArn", AwsArnUtils.Arn.of(SERVICE, region, account(), "cluster-snapshot/" + id)
                        .toString())
                .put("snapshotCreationTime", now()).put("snapshotName", name).put("snapshotType", type)
                .put("status", "CREATING");
        snapshot.set("subnetIds", from.path("subnetIds").deepCopy());
        snapshot.set("vpcSecurityGroupIds", from.path("vpcSecurityGroupIds").deepCopy());
        ObjectNode meta = snapshot.putObject("_");
        meta.put("seq", System.nanoTime());
        if (fromCluster) {
            ObjectNode clusterMeta = meta(from);
            meta.put("password", clusterMeta.path("password").asText())
                    .put("passwordInput", clusterMeta.path("passwordInput").asText())
                    .put("authType", from.path("authType").asText())
                    .put("shardCapacity", from.path("shardCapacity").asInt())
                    .put("shardCount", from.path("shardCount").asInt())
                    .put("shardInstanceCount", from.path("shardInstanceCount").asInt())
                    .put("backupRetentionPeriod", from.path("backupRetentionPeriod").asInt())
                    .put("preferredBackupWindow", from.path("preferredBackupWindow").asText());
        }
        meta.putObject("tags");
        return snapshot;
    }

    // ---------------------------------------------------------------- lifecycle

    /**
     * Puts a record in a transitional status that settles after the configured delay; a {@code null} settled status
     * removes the record once it settles.
     */
    private ObjectNode transition(String region, ObjectNode record, String status, String settled) {
        record.put("status", status);
        for (JsonNode shard : record.path("shards")) {
            ((ObjectNode) shard).put("status", status);
        }
        ObjectNode meta = meta(record);
        meta.put("settle", settled == null ? "GONE" : settled)
                .put("readyAt", System.currentTimeMillis() + config.services().docdbElastic().transitionMillis());
        save(region, record);
        ObjectNode current = settle(region, record);
        return current == null ? record : current;
    }

    /** Applies a transition whose time has come; null when the record is gone. */
    private ObjectNode settle(String region, ObjectNode record) {
        ObjectNode meta = meta(record);
        String next = meta.path("settle").asText(null);
        if (next == null || meta.path("readyAt").asLong() > System.currentTimeMillis()) {
            return record;
        }
        meta.remove("settle");
        meta.remove("readyAt");
        if ("GONE".equals(next)) {
            String kind = record.has("clusterName") ? CLUSTER : SNAPSHOT;
            storage.deleteForAccount(account(), key(region, kind, uuid(arnOf(record))));
            return null;
        }
        record.put("status", next);
        for (JsonNode shard : record.path("shards")) {
            ((ObjectNode) shard).put("status", next);
        }
        save(region, record);
        if (record.has("clusterName") && "ACTIVE".equals(next) && !meta.path("automated").asBoolean(false)) {
            meta.put("automated", true);
            save(region, record);
            automatedSnapshot(region, record);
        }
        return record;
    }

    /** The first automated backup of a cluster, taken when it becomes available. */
    private void automatedSnapshot(String region, ObjectNode cluster) {
        String name = cluster.path("clusterName").asText() + "-" + AUTOMATED.format(Instant.now());
        ObjectNode snapshot = snapshotOf(region, cluster, name, "AUTOMATED", cluster.path("kmsKeyId").asText());
        snapshot.put("status", "ACTIVE");
        save(region, snapshot);
    }

    private void save(String region, ObjectNode record) {
        String kind = record.has("clusterName") ? CLUSTER : SNAPSHOT;
        storage.putForAccount(account(), key(region, kind, uuid(arnOf(record))), record);
    }

    private static String arnOf(ObjectNode record) {
        return record.has("clusterName") ? record.path("clusterArn").asText() : record.path("snapshotArn").asText();
    }

    // ---------------------------------------------------------------- lookups

    private List<ObjectNode> all(String region, String kind) {
        String prefix = key(region, kind, "");
        List<ObjectNode> records = new ArrayList<>(storage.scanForAccount(account(),
                key -> key.startsWith(prefix)));
        records.sort(Comparator.comparingLong(record -> record.path("_").path("seq").asLong()));
        List<ObjectNode> live = new ArrayList<>();
        for (ObjectNode record : records) {
            ObjectNode settled = settle(region, record);
            if (settled != null) {
                live.add(settled);
            }
        }
        return live;
    }

    private Optional<ObjectNode> clusterNamed(String region, String name) {
        return all(region, CLUSTER).stream().filter(cluster -> name.equals(cluster.path("clusterName").asText()))
                .findFirst();
    }

    private void requireFreeSnapshotName(String region, String name) {
        for (ObjectNode snapshot : all(region, SNAPSHOT)) {
            if (name.equals(snapshot.path("snapshotName").asText())) {
                throw conflict("A snapshot already exists with given name " + name + ".", name, SNAPSHOT);
            }
        }
    }

    /** The stored cluster an ARN names, settled; ResourceNotFoundException when there is none. */
    private ObjectNode cluster(String region, String arn) {
        return record(region, arn, CLUSTER);
    }

    private ObjectNode snapshot(String region, String arn) {
        return view(snapshotRecord(region, arn));
    }

    private ObjectNode snapshotRecord(String region, String arn) {
        return record(region, arn, SNAPSHOT);
    }

    private ObjectNode record(String region, String arn, String kind) {
        Matcher matcher = RESOURCE_ARN.matcher(arn);
        if (!matcher.matches() || !kind.equals(matcher.group(3))) {
            throw invalid("Invalid " + (CLUSTER.equals(kind) ? "cluster" : "snapshot") + " ARN: " + arn);
        }
        String what = CLUSTER.equals(kind) ? "Cluster" : "Snapshot";
        ObjectNode record = null;
        if (region.equals(matcher.group(1)) && account().equals(matcher.group(2))) {
            record = storage.getForAccount(account(), key(region, kind, matcher.group(4)))
                    .map(stored -> settle(region, stored)).orElse(null);
        }
        if (record == null) {
            throw notFound(what + " " + arn + " not found.", arn, kind);
        }
        return record;
    }

    /** A cluster's or snapshot's record, for tagging. */
    private ObjectNode taggable(String region, String arn) {
        Matcher matcher = RESOURCE_ARN.matcher(arn);
        if (!matcher.matches()) {
            throw invalid("Invalid resource ARN: " + arn);
        }
        return record(region, arn, matcher.group(3));
    }

    /**
     * The snapshot a copy or restore reads. A source the caller cannot see is reported as AWS reports it: not
     * authorized on the resource, which discloses nothing about whether it exists.
     */
    private ObjectNode sourceSnapshot(String region, String arn, String operation) {
        ObjectNode snapshot;
        try {
            snapshot = snapshotRecord(region, arn);
        } catch (AwsException e) {
            if (!"ResourceNotFoundException".equals(e.getErrorCode())) {
                throw e;
            }
            throw new AwsException("AccessDeniedException", "User is not authorized to perform: docdb-elastic:"
                    + operation + " on resource: " + arn, 403);
        }
        if (!"ACTIVE".equals(snapshot.path("status").asText())) {
            throw invalid("Snapshot " + snapshot.path("snapshotName").asText() + " is in "
                    + snapshot.path("status").asText() + " state; it must be ACTIVE.", "other");
        }
        return snapshot;
    }

    private void requireActive(ObjectNode cluster, boolean conflict) {
        String status = cluster.path("status").asText();
        if ("ACTIVE".equals(status)) {
            return;
        }
        String message = "Cluster " + cluster.path("clusterName").asText() + " is in " + status
                + " state; it must be ACTIVE.";
        throw conflict ? conflict(message, cluster.path("clusterArn").asText(), CLUSTER) : invalid(message, "other");
    }

    private ObjectNode pending(ObjectNode cluster) {
        ObjectNode result = mapper.createObjectNode();
        result.put("resourceArn", cluster.path("clusterArn").asText());
        result.set("pendingMaintenanceActionDetails", meta(cluster).path("pending").deepCopy());
        return result;
    }

    // ---------------------------------------------------------------- input

    private String name(JsonNode input, String field) {
        String name = text(input, field);
        if (name.length() > 63 || !NAME.matcher(name).matches() || name.endsWith("-")) {
            throw invalid(field + " must begin with a letter, contain only letters, numbers and hyphens, be 1 to 63 "
                    + "characters long, and not end with a hyphen or contain two consecutive hyphens.");
        }
        return name.toLowerCase(Locale.ROOT);
    }

    /** The admin password the cluster's MongoDB gets: as given, or read from the Secrets Manager secret named. */
    private String password(String authType, String value, String region) {
        if ("SECRET_ARN".equals(authType)) {
            SecretVersion secret;
            try {
                secret = secrets.getSecretValue(value, null, null, region);
            } catch (AwsException e) {
                throw invalid("The secret " + value + " does not exist or cannot be read.");
            }
            String text = secret.getSecretString();
            if (text == null) {
                throw invalid("The secret " + value + " has no string value.");
            }
            try {
                JsonNode json = mapper.readTree(text);
                if (json != null && json.hasNonNull("password")) {
                    return json.get("password").asText();
                }
            } catch (IOException e) {
                LOG.debugv("Secret {0} is not JSON; using its whole value as the password", value);
            }
            return text;
        }
        if (value.length() < 8 || value.length() > 100) {
            throw invalid("adminUserPassword must be 8 to 100 characters long.");
        }
        for (char c : value.toCharArray()) {
            if (c < 0x20 || c > 0x7e || c == '/' || c == '"' || c == '@') {
                throw invalid("adminUserPassword can contain printable ASCII characters other than /, \" and @.");
            }
        }
        return value;
    }

    private String kmsKey(String kmsKeyId, String region) {
        if (kmsKeyId == null || kmsKeyId.isBlank()) {
            return DEFAULT_KMS_KEY;
        }
        KmsKey key;
        try {
            key = kms.describeKey(kmsKeyId, region);
        } catch (AwsException e) {
            throw invalid("The KMS key " + kmsKeyId + " does not exist or is not accessible.");
        }
        if (!key.isEnabled() || "PendingDeletion".equals(key.getKeyState())) {
            throw invalid("The KMS key " + kmsKeyId + " is not enabled.");
        }
        return key.getArn();
    }

    private static int shardCapacity(int value) {
        if (!SHARD_CAPACITIES.contains(value)) {
            throw invalid("shardCapacity must be one of 2, 4, 8, 16, 32 or 64.");
        }
        return value;
    }

    private static int range(JsonNode input, String field, int min, int max, int fallback) {
        if (!input.hasNonNull(field)) {
            if (fallback < 0) {
                throw invalid(field + " is required.");
            }
            return fallback;
        }
        int value = input.get(field).asInt();
        if (value < min || value > max) {
            throw invalid(field + " must be between " + min + " and " + max + ".");
        }
        return value;
    }

    /** A daily backup window of at least 30 minutes, or a random half hour. */
    private String backupWindow(String window) {
        if (window == null) {
            int start = random.nextInt(24 * 60 - 30);
            return clock(start) + "-" + clock(start + 30);
        }
        Matcher matcher = BACKUP_WINDOW.matcher(window);
        if (!matcher.matches()) {
            throw invalid("preferredBackupWindow must be in the format hh24:mi-hh24:mi.");
        }
        int start = Integer.parseInt(matcher.group(1)) * 60 + Integer.parseInt(matcher.group(2));
        int end = Integer.parseInt(matcher.group(3)) * 60 + Integer.parseInt(matcher.group(4));
        if (Math.floorMod(end - start, 24 * 60) < 30) {
            throw invalid("preferredBackupWindow must be at least 30 minutes.");
        }
        return window;
    }

    /** A weekly maintenance window of at least 30 minutes, or a random half hour. */
    private String maintenanceWindow(String window) {
        if (window == null) {
            int start = random.nextInt(7 * 24 * 60 - 30);
            return weekly(start) + "-" + weekly(start + 30);
        }
        String lower = window.toLowerCase(Locale.ROOT);
        Matcher matcher = MAINTENANCE_WINDOW.matcher(lower);
        if (!matcher.matches()) {
            throw invalid("preferredMaintenanceWindow must be in the format ddd:hh24:mi-ddd:hh24:mi.");
        }
        int start = DAYS.indexOf(matcher.group(1)) * 1440 + Integer.parseInt(matcher.group(2)) * 60
                + Integer.parseInt(matcher.group(3));
        int end = DAYS.indexOf(matcher.group(4)) * 1440 + Integer.parseInt(matcher.group(5)) * 60
                + Integer.parseInt(matcher.group(6));
        if (Math.floorMod(end - start, 7 * 1440) < 30) {
            throw invalid("preferredMaintenanceWindow must be at least 30 minutes.");
        }
        return lower;
    }

    private static String clock(int minutes) {
        return String.format("%02d:%02d", minutes / 60 % 24, minutes % 60);
    }

    private static String weekly(int minutes) {
        return DAYS.get(minutes / 1440 % 7) + ":" + clock(minutes % 1440);
    }

    /** A cluster's or snapshot's tags: the existing ones with these added, checked against AWS's limits. */
    private ObjectNode tags(JsonNode input, Map<String, String> existing) {
        Map<String, String> merged = new LinkedHashMap<>(existing);
        if (input != null && input.isObject()) {
            input.fields().forEachRemaining(entry -> merged.put(entry.getKey(), entry.getValue().asText()));
        }
        if (merged.size() > 50) {
            throw invalid("A resource can have at most 50 tags.");
        }
        ObjectNode tags = mapper.createObjectNode();
        for (Map.Entry<String, String> entry : merged.entrySet()) {
            String key = entry.getKey();
            if (key.isEmpty() || key.length() > 128 || entry.getValue().length() > 256) {
                throw invalid("Tag keys must be 1 to 128 characters and values at most 256.");
            }
            if (key.toLowerCase(Locale.ROOT).startsWith("aws:") && !existing.containsKey(key)) {
                throw invalid("Tag keys cannot begin with aws:.");
            }
            tags.put(key, entry.getValue());
        }
        return tags;
    }

    private static Map<String, String> tagMap(JsonNode tags) {
        Map<String, String> map = new LinkedHashMap<>();
        tags.fields().forEachRemaining(entry -> map.put(entry.getKey(), entry.getValue().asText()));
        return map;
    }

    private static String text(JsonNode input, String field) {
        JsonNode value = input.get(field);
        if (value == null || value.isNull() || value.asText().isEmpty()) {
            throw invalid(field + " is required.");
        }
        return value.asText();
    }

    private static String optional(JsonNode input, String field) {
        JsonNode value = input.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static List<String> strings(JsonNode list) {
        List<String> values = new ArrayList<>();
        if (list != null) {
            list.forEach(item -> values.add(item.asText()));
        }
        return values;
    }

    // ---------------------------------------------------------------- output

    private ObjectNode page(List<ObjectNode> items, JsonNode input, String field, int max) {
        int limit = input.hasNonNull("maxResults") ? input.get("maxResults").asInt() : max;
        Map<ObjectNode, Integer> positions = new IdentityHashMap<>();
        for (int index = 0; index < items.size(); index++) {
            positions.put(items.get(index), index);
        }
        PaginatedResult<ObjectNode> paged = Pagination.paginate(items,
                item -> String.format("%09d", positions.get(item)), Math.min(limit, max),
                input.path("nextToken").asText(null), field, bad -> invalid("The nextToken is not valid."));
        ObjectNode response = mapper.createObjectNode();
        response.putArray(field).addAll(paged.items());
        if (paged.nextToken() != null) {
            response.put("nextToken", paged.nextToken());
        }
        return response;
    }

    private ObjectNode wrap(String field, ObjectNode record) {
        ObjectNode response = mapper.createObjectNode();
        response.set(field, view(record));
        return response;
    }

    private static ObjectNode view(ObjectNode record) {
        ObjectNode copy = record.deepCopy();
        copy.remove("_");
        return copy;
    }

    private static ObjectNode meta(ObjectNode record) {
        return (ObjectNode) record.get("_");
    }

    private String shardId() {
        StringBuilder id = new StringBuilder();
        for (int index = 0; index < 12; index++) {
            id.append("0123456789abcdef".charAt(random.nextInt(16)));
        }
        return id.toString();
    }

    private String dnsSuffix() {
        return config.hostname().orElse(EmbeddedDnsServer.DEFAULT_SUFFIX);
    }

    private String account() {
        return storage.accountId();
    }

    private static String uuid(String arn) {
        return arn.substring(arn.lastIndexOf('/') + 1);
    }

    private static String key(String region, String kind, String id) {
        return region + "/" + kind + "/" + id;
    }

    private static String now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS).toString();
    }

    // ---------------------------------------------------------------- errors

    static AwsException invalid(String message) {
        return invalid(message, "fieldValidationFailed");
    }

    static AwsException invalid(String message, String reason) {
        return new AwsException("ValidationException", message, 400, Map.of("reason", reason));
    }

    private static AwsException notFound(String message, String id, String type) {
        return new AwsException("ResourceNotFoundException", message, 404, Map.of("resourceId", id,
                "resourceType", type));
    }

    private static AwsException conflict(String message, String id, String type) {
        return new AwsException("ConflictException", message, 409, Map.of("resourceId", id, "resourceType", type));
    }
}
