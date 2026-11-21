package io.floci.aws.services.codeconnections;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestConstraintCheck;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.kms.KmsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * AWS CodeConnections: hosts for self-managed providers, connections to source providers, repository links, and
 * Git sync configurations that keep a CloudFormation stack in step with a file in a repository. A connection is
 * {@code PENDING} until its handshake with the provider, which happens in the console, outside the API; so every
 * account and Region starts with one {@code AVAILABLE} GitHub connection ({@link #DEMO_CONNECTION_ID}, owner
 * {@link #DEMO_OWNER}) and one repository link on it ({@link #DEMO_LINK_ID}, {@code floci-demo/app}). Floci can't
 * reach the providers, so no commit is ever synced: a sync configuration has no sync status until one is.
 */
@ApplicationScoped
public class CodeConnectionsService implements Resettable {
    static final String DEMO_CONNECTION_ID = "f10c1000-0000-4000-8000-0000000000c1";
    static final String DEMO_CONNECTION_NAME = "floci-demo";
    static final String DEMO_LINK_ID = "f10c1000-0000-4000-8000-0000000000c2";
    static final String DEMO_OWNER = "floci-demo";
    static final String DEMO_REPOSITORY = "app";
    private static final List<String> SELF_MANAGED = List.of("GitHubEnterpriseServer", "GitLabSelfManaged");
    private static final int MAX_TAGS = 200;

    private static final RequestConstraintCheck SHAPES = RequestConstraintCheck.load(
            "aws/codeconnections-request-shapes.json", "CodeConnections request shapes",
            new RequestConstraintCheck.Messages() {
                @Override
                public AwsException type(String path, String type) {
                    return invalid(path + " must be a " + type + ".");
                }

                @Override
                public AwsException bounds(String path, String type, Long min, Long max, long size) {
                    return invalid(path + " is outside the allowed bounds" + (min == null ? "" : " (min " + min
                            + ")") + (max == null ? "" : " (max " + max + ")") + ".");
                }

                @Override
                public AwsException pattern(String path, String pattern) {
                    return invalid(path + " does not match the pattern " + pattern + ".");
                }

                @Override
                public AwsException enumValue(String path, List<String> values) {
                    return invalid(path + " must be one of " + values + ".");
                }

                @Override
                public AwsException required(String path) {
                    return invalid(path + " is required.");
                }
            });

    private final AccountAwareStorageBackend<ObjectNode> connections;
    private final AccountAwareStorageBackend<ObjectNode> hosts;
    private final AccountAwareStorageBackend<ObjectNode> links;
    private final AccountAwareStorageBackend<ObjectNode> syncs;
    private final AccountAwareStorageBackend<ObjectNode> seeded;
    private final Instance<KmsService> kms;
    private final RegionResolver regionResolver;
    private final ObjectMapper mapper;
    private final SecureRandom random = new SecureRandom();

    @Inject
    public CodeConnectionsService(StorageFactory storageFactory, Instance<KmsService> kms,
                                  RegionResolver regionResolver, ObjectMapper mapper) {
        this.connections = store(storageFactory, "codeconnections-connections.json");
        this.hosts = store(storageFactory, "codeconnections-hosts.json");
        this.links = store(storageFactory, "codeconnections-repository-links.json");
        this.syncs = store(storageFactory, "codeconnections-sync-configurations.json");
        this.seeded = store(storageFactory, "codeconnections-seeded.json");
        this.kms = kms;
        this.regionResolver = regionResolver;
        this.mapper = mapper;
    }

    private static AccountAwareStorageBackend<ObjectNode> store(StorageFactory factory, String file) {
        return factory.create("codeconnections", file, new TypeReference<Map<String, ObjectNode>>() {});
    }

    static void check(String operation, JsonNode request) {
        SHAPES.check(operation, request);
    }

    /** Seeds the Region's demo connection and repository link the first time the account uses the Region. */
    private void seed(String region) {
        if (seeded.get(region).isPresent()) {
            return;
        }
        ObjectNode connection = mapper.createObjectNode();
        connection.put("ConnectionName", DEMO_CONNECTION_NAME);
        connection.put("ConnectionArn", arn(region, "connection/" + DEMO_CONNECTION_ID));
        connection.put("ProviderType", "GitHub");
        connection.put("OwnerAccountId", regionResolver.getAccountId());
        connection.put("ConnectionStatus", "AVAILABLE");
        connection.putArray("Tags");
        connection.put("CreatedAt", 0);
        connections.put(region + "/" + DEMO_CONNECTION_ID, connection);
        ObjectNode link = mapper.createObjectNode();
        link.put("ConnectionArn", connection.path("ConnectionArn").asText());
        link.put("OwnerId", DEMO_OWNER);
        link.put("ProviderType", "GitHub");
        link.put("RepositoryLinkArn", arn(region, "repository-link/" + DEMO_LINK_ID));
        link.put("RepositoryLinkId", DEMO_LINK_ID);
        link.put("RepositoryName", DEMO_REPOSITORY);
        link.putArray("Tags");
        link.put("CreatedAt", 0);
        links.put(region + "/" + DEMO_LINK_ID, link);
        seeded.put(region, mapper.createObjectNode().put("seeded", true));
    }

    // ── Hosts ──────────────────────────────────────────────────────────────

    public synchronized ObjectNode createHost(JsonNode in, String region) {
        check("CreateHost", in);
        seed(region);
        String type = in.path("ProviderType").asText();
        if (!SELF_MANAGED.contains(type)) {
            throw invalid("A host is for a self-managed provider: GitHubEnterpriseServer or GitLabSelfManaged.");
        }
        String endpoint = in.path("ProviderEndpoint").asText();
        if (!endpoint.startsWith("https://")) {
            throw invalid("ProviderEndpoint must be an https:// URL.");
        }
        if (scan(hosts, region).size() >= 100) {
            throw new AwsException("LimitExceededException", "The account has the maximum of 100 hosts.", 429);
        }
        String name = in.path("Name").asText();
        String id = name.toLowerCase().replaceAll("[^a-z0-9-]", "-") + "-" + HexFormat.of().formatHex(bytes(4));
        ObjectNode host = mapper.createObjectNode();
        host.put("Name", name);
        host.put("HostArn", arn(region, "host/" + id));
        host.put("ProviderType", type);
        host.put("ProviderEndpoint", endpoint);
        if (in.has("VpcConfiguration")) {
            host.set("VpcConfiguration", vpc(in.path("VpcConfiguration")));
        }
        host.put("Status", "PENDING");
        host.put("StatusMessage", "The host is waiting for its app to be set up with the provider.");
        host.set("Tags", tags(in.path("Tags")));
        host.put("CreatedAt", System.currentTimeMillis());
        hosts.put(region + "/" + id, host);
        ObjectNode out = mapper.createObjectNode().put("HostArn", host.path("HostArn").asText());
        out.set("Tags", host.path("Tags").deepCopy());
        return out;
    }

    public synchronized ObjectNode getHost(JsonNode in, String region) {
        check("GetHost", in);
        ObjectNode host = requireHost(in.path("HostArn").asText(), region);
        ObjectNode out = mapper.createObjectNode();
        copy(host, out, "Name", "Status", "ProviderType", "ProviderEndpoint", "VpcConfiguration");
        return out;
    }

    public synchronized void updateHost(JsonNode in, String region) {
        check("UpdateHost", in);
        ObjectNode host = requireHost(in.path("HostArn").asText(), region);
        if ("VPC_CONFIG_INITIALIZING".equals(host.path("Status").asText())) {
            throw new AwsException("ConflictException", "The host is being set up; try again later.", 409);
        }
        if (in.hasNonNull("ProviderEndpoint")) {
            String endpoint = in.path("ProviderEndpoint").asText();
            if (!endpoint.startsWith("https://")) {
                throw invalid("ProviderEndpoint must be an https:// URL.");
            }
            host.put("ProviderEndpoint", endpoint);
        }
        if (in.has("VpcConfiguration")) {
            host.set("VpcConfiguration", vpc(in.path("VpcConfiguration")));
        }
        hosts.put(key(region, host.path("HostArn").asText()), host);
    }

    /** Deletes a host with no connections; its connections must be deleted first. */
    public synchronized void deleteHost(JsonNode in, String region) {
        check("DeleteHost", in);
        String arn = in.path("HostArn").asText();
        requireHost(arn, region);
        if (scan(connections, region).stream().anyMatch(c -> arn.equals(c.path("HostArn").asText()))) {
            throw new AwsException("ResourceUnavailableException", "The host has connections; delete them first.",
                    400);
        }
        hosts.delete(key(region, arn));
    }

    public synchronized ObjectNode listHosts(JsonNode in, String region) {
        check("ListHosts", in);
        seed(region);
        ObjectNode out = mapper.createObjectNode();
        ArrayNode list = out.putArray("Hosts");
        page(scan(hosts, region), in, out).forEach(h -> {
            ObjectNode summary = list.addObject();
            copy(h, summary, "Name", "HostArn", "ProviderType", "ProviderEndpoint", "VpcConfiguration", "Status",
                    "StatusMessage");
        });
        return out;
    }

    private ObjectNode vpc(JsonNode requested) {
        if (!requested.path("SubnetIds").isArray() || requested.path("SubnetIds").isEmpty()
                || !requested.path("SecurityGroupIds").isArray() || requested.path("SecurityGroupIds").isEmpty()) {
            throw invalid("VpcConfiguration needs a VpcId, SubnetIds and SecurityGroupIds.");
        }
        return (ObjectNode) requested.deepCopy();
    }

    private ObjectNode requireHost(String arn, String region) {
        return hosts.get(key(region, arn)).orElseThrow(() -> notFound("The host " + arn + " doesn't exist."));
    }

    // ── Connections ────────────────────────────────────────────────────────

    public synchronized ObjectNode createConnection(JsonNode in, String region) {
        check("CreateConnection", in);
        seed(region);
        String type = in.path("ProviderType").asText(null);
        String hostArn = in.path("HostArn").asText(null);
        if (hostArn != null) {
            ObjectNode host = requireHost(hostArn, region);
            if (type != null && !type.equals(host.path("ProviderType").asText())) {
                throw invalid("ProviderType " + type + " doesn't match the host's, "
                        + host.path("ProviderType").asText() + ".");
            }
            type = host.path("ProviderType").asText();
        } else if (type == null) {
            throw invalid("A connection needs a ProviderType, or a HostArn for a self-managed provider.");
        } else if (SELF_MANAGED.contains(type)) {
            throw invalid("A " + type + " connection needs the HostArn of its host.");
        }
        if (scan(connections, region).size() >= 250) {
            throw new AwsException("LimitExceededException", "The account has the maximum of 250 connections.", 429);
        }
        String id = UUID.randomUUID().toString();
        ObjectNode connection = mapper.createObjectNode();
        connection.put("ConnectionName", in.path("ConnectionName").asText());
        connection.put("ConnectionArn", arn(region, "connection/" + id));
        connection.put("ProviderType", type);
        connection.put("OwnerAccountId", regionResolver.getAccountId());
        connection.put("ConnectionStatus", "PENDING");
        if (hostArn != null) {
            connection.put("HostArn", hostArn);
        }
        connection.set("Tags", tags(in.path("Tags")));
        connection.put("CreatedAt", System.currentTimeMillis());
        connections.put(region + "/" + id, connection);
        ObjectNode out = mapper.createObjectNode().put("ConnectionArn", connection.path("ConnectionArn").asText());
        out.set("Tags", connection.path("Tags").deepCopy());
        return out;
    }

    public synchronized ObjectNode getConnection(JsonNode in, String region) {
        check("GetConnection", in);
        seed(region);
        return mapper.createObjectNode().set("Connection", renderConnection(requireConnection(
                in.path("ConnectionArn").asText(), region)));
    }

    /** Deletes a connection; its repository links stop working, as on AWS. */
    public synchronized void deleteConnection(JsonNode in, String region) {
        check("DeleteConnection", in);
        seed(region);
        String arn = in.path("ConnectionArn").asText();
        requireConnection(arn, region);
        connections.delete(key(region, arn));
    }

    public synchronized ObjectNode listConnections(JsonNode in, String region) {
        check("ListConnections", in);
        seed(region);
        String type = in.path("ProviderTypeFilter").asText(null);
        String host = in.path("HostArnFilter").asText(null);
        if (host != null) {
            requireHost(host, region);
        }
        List<ObjectNode> matching = scan(connections, region).stream()
                .filter(c -> type == null || type.equals(c.path("ProviderType").asText()))
                .filter(c -> host == null || host.equals(c.path("HostArn").asText())).toList();
        ObjectNode out = mapper.createObjectNode();
        ArrayNode list = out.putArray("Connections");
        page(matching, in, out).forEach(c -> list.add(renderConnection(c)));
        return out;
    }

    private ObjectNode renderConnection(ObjectNode stored) {
        ObjectNode out = mapper.createObjectNode();
        copy(stored, out, "ConnectionName", "ConnectionArn", "ProviderType", "OwnerAccountId", "ConnectionStatus",
                "HostArn");
        return out;
    }

    private ObjectNode requireConnection(String arn, String region) {
        return connections.get(key(region, arn)).filter(c -> arn.equals(c.path("ConnectionArn").asText()))
                .orElseThrow(() -> notFound("The connection " + arn + " doesn't exist."));
    }

    // ── Repository links ───────────────────────────────────────────────────

    public synchronized ObjectNode createLink(JsonNode in, String region) {
        check("CreateRepositoryLink", in);
        seed(region);
        ObjectNode connection = connections.get(key(region, in.path("ConnectionArn").asText()))
                .orElseThrow(() -> invalid("The connection " + in.path("ConnectionArn").asText()
                        + " doesn't exist."));
        if (!"AVAILABLE".equals(connection.path("ConnectionStatus").asText())) {
            throw invalid("The connection " + connection.path("ConnectionArn").asText() + " is "
                    + connection.path("ConnectionStatus").asText() + "; complete its handshake in the console before "
                    + "linking a repository.");
        }
        String owner = in.path("OwnerId").asText();
        String repository = in.path("RepositoryName").asText();
        boolean taken = scan(links, region).stream().anyMatch(l -> owner.equals(l.path("OwnerId").asText())
                && repository.equals(l.path("RepositoryName").asText())
                && connection.path("ProviderType").asText().equals(l.path("ProviderType").asText()));
        if (taken) {
            throw new AwsException("ResourceAlreadyExistsException", "The repository " + owner + "/" + repository
                    + " is already linked.", 409);
        }
        checkKey(in.path("EncryptionKeyArn").asText(null), region);
        String id = UUID.randomUUID().toString();
        ObjectNode link = mapper.createObjectNode();
        link.put("ConnectionArn", connection.path("ConnectionArn").asText());
        copy(in, link, "EncryptionKeyArn");
        link.put("OwnerId", owner);
        link.put("ProviderType", connection.path("ProviderType").asText());
        link.put("RepositoryLinkArn", arn(region, "repository-link/" + id));
        link.put("RepositoryLinkId", id);
        link.put("RepositoryName", repository);
        link.set("Tags", tags(in.path("Tags")));
        link.put("CreatedAt", System.currentTimeMillis());
        links.put(region + "/" + id, link);
        return mapper.createObjectNode().set("RepositoryLinkInfo", renderLink(link));
    }

    public synchronized ObjectNode getLink(JsonNode in, String region) {
        check("GetRepositoryLink", in);
        seed(region);
        return mapper.createObjectNode().set("RepositoryLinkInfo", renderLink(requireLink(
                in.path("RepositoryLinkId").asText(), region)));
    }

    public synchronized ObjectNode updateLink(JsonNode in, String region) {
        check("UpdateRepositoryLink", in);
        seed(region);
        ObjectNode link = requireLink(in.path("RepositoryLinkId").asText(), region);
        if (in.hasNonNull("ConnectionArn")) {
            ObjectNode connection = connections.get(key(region, in.path("ConnectionArn").asText()))
                    .orElseThrow(() -> invalid("The connection " + in.path("ConnectionArn").asText()
                            + " doesn't exist."));
            if (!connection.path("ProviderType").asText().equals(link.path("ProviderType").asText())) {
                throw invalid("The connection's provider type doesn't match the repository's.");
            }
            link.put("ConnectionArn", connection.path("ConnectionArn").asText());
        }
        if (in.hasNonNull("EncryptionKeyArn")) {
            checkKey(in.path("EncryptionKeyArn").asText(), region);
            link.put("EncryptionKeyArn", in.path("EncryptionKeyArn").asText());
        }
        links.put(region + "/" + link.path("RepositoryLinkId").asText(), link);
        return mapper.createObjectNode().set("RepositoryLinkInfo", renderLink(link));
    }

    public synchronized void deleteLink(JsonNode in, String region) {
        check("DeleteRepositoryLink", in);
        seed(region);
        String id = in.path("RepositoryLinkId").asText();
        requireLink(id, region);
        if (scan(syncs, region).stream().anyMatch(s -> id.equals(s.path("RepositoryLinkId").asText()))) {
            throw new AwsException("SyncConfigurationStillExistsException", "The repository link has sync "
                    + "configurations; delete them first.", 409);
        }
        links.delete(region + "/" + id);
    }

    public synchronized ObjectNode listLinks(JsonNode in, String region) {
        check("ListRepositoryLinks", in);
        seed(region);
        ObjectNode out = mapper.createObjectNode();
        ArrayNode list = out.putArray("RepositoryLinks");
        page(scan(links, region), in, out).forEach(l -> list.add(renderLink(l)));
        return out;
    }

    private ObjectNode renderLink(ObjectNode stored) {
        ObjectNode out = mapper.createObjectNode();
        copy(stored, out, "ConnectionArn", "EncryptionKeyArn", "OwnerId", "ProviderType", "RepositoryLinkArn",
                "RepositoryLinkId", "RepositoryName");
        return out;
    }

    private ObjectNode requireLink(String id, String region) {
        return links.get(region + "/" + id).orElseThrow(() -> notFound("The repository link " + id
                + " doesn't exist."));
    }

    private void checkKey(String key, String region) {
        if (key != null && kms.isResolvable() && kms.get().findKeyArn(key, region).isEmpty()) {
            throw invalid("The KMS key " + key + " doesn't exist.");
        }
    }

    // ── Sync configurations ────────────────────────────────────────────────

    public synchronized ObjectNode createSync(JsonNode in, String region) {
        check("CreateSyncConfiguration", in);
        seed(region);
        String name = in.path("ResourceName").asText();
        String type = in.path("SyncType").asText();
        if (syncs.get(region + "/" + type + "/" + name).isPresent()) {
            throw new AwsException("ResourceAlreadyExistsException", "A " + type + " sync configuration for " + name
                    + " already exists.", 409);
        }
        ObjectNode link = links.get(region + "/" + in.path("RepositoryLinkId").asText()).orElseThrow(() ->
                invalid("The repository link " + in.path("RepositoryLinkId").asText() + " doesn't exist."));
        ObjectNode sync = mapper.createObjectNode();
        sync.put("Branch", in.path("Branch").asText());
        sync.put("ConfigFile", in.path("ConfigFile").asText());
        sync.put("OwnerId", link.path("OwnerId").asText());
        sync.put("ProviderType", link.path("ProviderType").asText());
        sync.put("RepositoryLinkId", link.path("RepositoryLinkId").asText());
        sync.put("RepositoryName", link.path("RepositoryName").asText());
        sync.put("ResourceName", name);
        sync.put("RoleArn", in.path("RoleArn").asText());
        sync.put("SyncType", type);
        sync.put("PublishDeploymentStatus", in.path("PublishDeploymentStatus").asText("ENABLED"));
        sync.put("TriggerResourceUpdateOn", in.path("TriggerResourceUpdateOn").asText("ANY_CHANGE"));
        sync.put("PullRequestComment", in.path("PullRequestComment").asText("DISABLED"));
        sync.put("CreatedAt", System.currentTimeMillis());
        syncs.put(region + "/" + type + "/" + name, sync);
        return mapper.createObjectNode().set("SyncConfiguration", renderSync(sync));
    }

    public synchronized ObjectNode getSync(JsonNode in, String region) {
        check("GetSyncConfiguration", in);
        return mapper.createObjectNode().set("SyncConfiguration", renderSync(requireSync(in, region)));
    }

    public synchronized ObjectNode updateSync(JsonNode in, String region) {
        check("UpdateSyncConfiguration", in);
        seed(region);
        ObjectNode sync = requireSync(in, region);
        if (in.hasNonNull("RepositoryLinkId")) {
            ObjectNode link = links.get(region + "/" + in.path("RepositoryLinkId").asText()).orElseThrow(() ->
                    invalid("The repository link " + in.path("RepositoryLinkId").asText() + " doesn't exist."));
            sync.put("RepositoryLinkId", link.path("RepositoryLinkId").asText());
            sync.put("OwnerId", link.path("OwnerId").asText());
            sync.put("RepositoryName", link.path("RepositoryName").asText());
            sync.put("ProviderType", link.path("ProviderType").asText());
        }
        for (String field : List.of("Branch", "ConfigFile", "RoleArn", "PublishDeploymentStatus",
                "TriggerResourceUpdateOn", "PullRequestComment")) {
            if (in.hasNonNull(field)) {
                sync.put(field, in.path(field).asText());
            }
        }
        syncs.put(region + "/" + sync.path("SyncType").asText() + "/" + sync.path("ResourceName").asText(), sync);
        return mapper.createObjectNode().set("SyncConfiguration", renderSync(sync));
    }

    /** Deletes a sync configuration; deleting one that doesn't exist succeeds, as on AWS. */
    public synchronized void deleteSync(JsonNode in, String region) {
        check("DeleteSyncConfiguration", in);
        syncs.delete(region + "/" + in.path("SyncType").asText() + "/" + in.path("ResourceName").asText());
    }

    public synchronized ObjectNode listSyncs(JsonNode in, String region) {
        check("ListSyncConfigurations", in);
        seed(region);
        String link = in.path("RepositoryLinkId").asText();
        requireLink(link, region);
        List<ObjectNode> matching = scan(syncs, region).stream()
                .filter(s -> link.equals(s.path("RepositoryLinkId").asText())
                        && in.path("SyncType").asText().equals(s.path("SyncType").asText())).toList();
        ObjectNode out = mapper.createObjectNode();
        ArrayNode list = out.putArray("SyncConfigurations");
        page(matching, in, out).forEach(s -> list.add(renderSync(s)));
        return out;
    }

    /** The definitions the repository's sync configurations give: branch, config file directory, parent, target. */
    public synchronized ObjectNode listDefinitions(JsonNode in, String region) {
        check("ListRepositorySyncDefinitions", in);
        seed(region);
        String link = in.path("RepositoryLinkId").asText();
        requireLink(link, region);
        ObjectNode out = mapper.createObjectNode();
        ArrayNode list = out.putArray("RepositorySyncDefinitions");
        Map<String, ObjectNode> byKey = new LinkedHashMap<>();
        for (ObjectNode sync : scan(syncs, region)) {
            if (!link.equals(sync.path("RepositoryLinkId").asText())) {
                continue;
            }
            String file = sync.path("ConfigFile").asText();
            ObjectNode definition = mapper.createObjectNode();
            definition.put("Branch", sync.path("Branch").asText());
            definition.put("Directory", file.contains("/") ? file.substring(0, file.lastIndexOf('/')) : "");
            definition.put("Parent", sync.path("ResourceName").asText());
            definition.put("Target", sync.path("ResourceName").asText());
            byKey.put(definition.toString(), definition);
        }
        byKey.values().forEach(list::add);
        return out;
    }

    /** Never synced: Floci can't reach the provider, so no commit has been synced to report on. */
    public synchronized ObjectNode repositorySyncStatus(JsonNode in, String region) {
        check("GetRepositorySyncStatus", in);
        seed(region);
        requireLink(in.path("RepositoryLinkId").asText(), region);
        throw notFound("No sync has run for branch " + in.path("Branch").asText() + " of the repository yet.");
    }

    public synchronized ObjectNode resourceSyncStatus(JsonNode in, String region) {
        check("GetResourceSyncStatus", in);
        requireSync(in, region);
        throw notFound("No sync has run for " + in.path("ResourceName").asText() + " yet.");
    }

    public synchronized ObjectNode blockerSummary(JsonNode in, String region) {
        check("GetSyncBlockerSummary", in);
        requireSync(in, region);
        ObjectNode summary = mapper.createObjectNode();
        summary.put("ResourceName", in.path("ResourceName").asText());
        summary.putArray("LatestBlockers");
        return mapper.createObjectNode().set("SyncBlockerSummary", summary);
    }

    public synchronized ObjectNode updateBlocker(JsonNode in, String region) {
        check("UpdateSyncBlocker", in);
        requireSync(in, region);
        throw new AwsException("SyncBlockerDoesNotExistException", "The sync blocker " + in.path("Id").asText()
                + " doesn't exist.", 404);
    }

    private ObjectNode requireSync(JsonNode in, String region) {
        return syncs.get(region + "/" + in.path("SyncType").asText() + "/" + in.path("ResourceName").asText())
                .orElseThrow(() -> notFound("No " + in.path("SyncType").asText() + " sync configuration exists for "
                        + in.path("ResourceName").asText() + "."));
    }

    private ObjectNode renderSync(ObjectNode stored) {
        ObjectNode out = stored.deepCopy();
        out.remove("CreatedAt");
        return out;
    }

    // ── Tags ───────────────────────────────────────────────────────────────

    public synchronized ObjectNode listTags(JsonNode in, String region) {
        check("ListTagsForResource", in);
        seed(region);
        ObjectNode out = mapper.createObjectNode();
        out.set("Tags", tagged(in.path("ResourceArn").asText(), region).getValue().path("Tags").deepCopy());
        return out;
    }

    public synchronized void tag(JsonNode in, String region) {
        check("TagResource", in);
        seed(region);
        Map.Entry<AccountAwareStorageBackend<ObjectNode>, ObjectNode> tagged = tagged(in.path("ResourceArn").asText(),
                region);
        ArrayNode tags = tagged.getValue().withArray("Tags");
        for (JsonNode tag : in.path("Tags")) {
            for (int i = tags.size() - 1; i >= 0; i--) {
                if (tag.path("Key").asText().equals(tags.get(i).path("Key").asText())) {
                    tags.remove(i);
                }
            }
            tags.add(tag.deepCopy());
        }
        if (tags.size() > MAX_TAGS) {
            throw new AwsException("LimitExceededException", "A resource can have at most " + MAX_TAGS + " tags.", 429);
        }
        tagged.getKey().put(key(region, in.path("ResourceArn").asText()), tagged.getValue());
    }

    public synchronized void untag(JsonNode in, String region) {
        check("UntagResource", in);
        seed(region);
        Map.Entry<AccountAwareStorageBackend<ObjectNode>, ObjectNode> tagged = tagged(in.path("ResourceArn").asText(),
                region);
        ArrayNode tags = tagged.getValue().withArray("Tags");
        for (JsonNode key : in.path("TagKeys")) {
            for (int i = tags.size() - 1; i >= 0; i--) {
                if (key.asText().equals(tags.get(i).path("Key").asText())) {
                    tags.remove(i);
                }
            }
        }
        tagged.getKey().put(key(region, in.path("ResourceArn").asText()), tagged.getValue());
    }

    private Map.Entry<AccountAwareStorageBackend<ObjectNode>, ObjectNode> tagged(String arn, String region) {
        String resource = AwsArnUtils.isArn(arn) ? AwsArnUtils.parse(arn).resource() : "";
        AccountAwareStorageBackend<ObjectNode> store = resource.startsWith("connection/") ? connections
                : resource.startsWith("host/") ? hosts : resource.startsWith("repository-link/") ? links : null;
        Optional<ObjectNode> found = store == null ? Optional.empty() : store.get(key(region, arn));
        if (found.isEmpty()) {
            throw notFound("The resource " + arn + " doesn't exist.");
        }
        return Map.entry(store, found.get());
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    /** The store key of an ARN: its Region (or the request's) and the id after the resource type. */
    private static String key(String region, String arn) {
        if (!AwsArnUtils.isArn(arn)) {
            return region + "/" + arn;
        }
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        String resource = parsed.resource();
        return (parsed.region().isEmpty() ? region : parsed.region()) + "/"
                + resource.substring(resource.indexOf('/') + 1);
    }

    private List<ObjectNode> scan(AccountAwareStorageBackend<ObjectNode> store, String region) {
        return store.scan(k -> k.startsWith(region + "/")).stream()
                .sorted(Comparator.comparing(r -> r.path("CreatedAt").asLong())).toList();
    }

    private List<ObjectNode> page(List<ObjectNode> all, JsonNode in, ObjectNode out) {
        int max = in.path("MaxResults").asInt(100);
        int start = 0;
        if (in.hasNonNull("NextToken")) {
            try {
                start = Integer.parseInt(in.path("NextToken").asText());
            } catch (NumberFormatException e) {
                throw invalid("The NextToken isn't valid.");
            }
        }
        if (start < 0 || start > all.size()) {
            throw invalid("The NextToken isn't valid.");
        }
        int end = Math.min(all.size(), start + max);
        if (end < all.size()) {
            out.put("NextToken", String.valueOf(end));
        }
        return new ArrayList<>(all.subList(start, end));
    }

    private ArrayNode tags(JsonNode requested) {
        ArrayNode out = mapper.createArrayNode();
        requested.forEach(t -> out.add(t.deepCopy()));
        return out;
    }

    private static void copy(JsonNode from, ObjectNode to, String... fields) {
        for (String field : fields) {
            if (from.hasNonNull(field)) {
                to.set(field, from.get(field).deepCopy());
            }
        }
    }

    private String arn(String region, String resource) {
        return AwsArnUtils.Arn.of("codeconnections", region, regionResolver.getAccountId(), resource).toString();
    }

    private byte[] bytes(int n) {
        byte[] out = new byte[n];
        random.nextBytes(out);
        return out;
    }

    static AwsException invalid(String message) {
        return new AwsException("InvalidInputException", message, 400);
    }

    static AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 404);
    }

    @Override
    public void clear() {
        connections.clear();
        hosts.clear();
        links.clear();
        syncs.clear();
        seeded.clear();
    }
}
