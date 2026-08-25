package io.github.hectorvent.floci.services.codeconnections;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.codeconnections.model.CodeConnectionsConnection;
import io.github.hectorvent.floci.services.codeconnections.model.CodeConnectionsHost;
import io.github.hectorvent.floci.services.codeconnections.model.CodeConnectionsRepositoryLink;
import io.github.hectorvent.floci.services.codeconnections.model.CodeConnectionsSyncConfiguration;
import io.github.hectorvent.floci.services.codeconnections.model.CodeConnectionsVpcConfiguration;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * AWS CodeConnections (JSON 1.0, target prefix {@code CodeConnections_20231201.}).
 *
 * <p>Connections and hosts are created in {@code PENDING}; completing the OAuth /
 * host-setup handshake is a console-only step with no API, matching live AWS.
 * Repository links and sync configurations are still allowed against PENDING
 * connections so local Git-sync reconcilers can run.
 */
@ApplicationScoped
public class CodeConnectionsService implements Resettable {

    static final String SERVICE = "codeconnections";
    static final String DEFAULT_SYNC_TYPE = "CFN_STACK_SYNC";
    private static final String DEFAULT_PROVIDER = "GitHub";
    private static final String STATUS_PENDING = "PENDING";
    private static final int DEFAULT_MAX_RESULTS = 100;
    private static final int HOST_NAME_MAX = 64;
    private static final Set<String> PUBLISH_STATUS = Set.of("ENABLED", "DISABLED");
    private static final Set<String> TRIGGER_ON = Set.of("ANY_CHANGE", "FILE_CHANGE");
    private static final Set<String> PR_COMMENT = Set.of("ENABLED", "DISABLED");
    private static final Set<String> SELF_MANAGED = Set.of("GitHubEnterpriseServer", "GitLabSelfManaged");

    private final StorageBackend<String, CodeConnectionsConnection> connections;
    private final StorageBackend<String, CodeConnectionsHost> hosts;
    private final StorageBackend<String, CodeConnectionsRepositoryLink> links;
    private final StorageBackend<String, CodeConnectionsSyncConfiguration> syncs;
    private final RegionResolver regionResolver;

    @Inject
    public CodeConnectionsService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this(storageFactory.create(SERVICE, "codeconnections-connections.json",
                        new TypeReference<Map<String, CodeConnectionsConnection>>() {
                        }),
                storageFactory.create(SERVICE, "codeconnections-hosts.json",
                        new TypeReference<Map<String, CodeConnectionsHost>>() {
                        }),
                storageFactory.create(SERVICE, "codeconnections-repository-links.json",
                        new TypeReference<Map<String, CodeConnectionsRepositoryLink>>() {
                        }),
                storageFactory.create(SERVICE, "codeconnections-sync-configurations.json",
                        new TypeReference<Map<String, CodeConnectionsSyncConfiguration>>() {
                        }),
                regionResolver);
    }

    CodeConnectionsService(StorageBackend<String, CodeConnectionsConnection> connections,
                           StorageBackend<String, CodeConnectionsHost> hosts,
                           StorageBackend<String, CodeConnectionsRepositoryLink> links,
                           StorageBackend<String, CodeConnectionsSyncConfiguration> syncs,
                           RegionResolver regionResolver) {
        this.connections = connections;
        this.hosts = hosts;
        this.links = links;
        this.syncs = syncs;
        this.regionResolver = regionResolver;
    }

    @Override
    public void clear() {
        connections.clear();
        hosts.clear();
        links.clear();
        syncs.clear();
    }

    public CodeConnectionsHost createHost(String region, String name, String providerType, String providerEndpoint,
                                          CodeConnectionsVpcConfiguration vpc, Map<String, String> tags) {
        require(name, "Name");
        if (name.length() > HOST_NAME_MAX) {
            throw invalid("Name must be between 1 and 64 characters.");
        }
        require(providerType, "ProviderType");
        require(providerEndpoint, "ProviderEndpoint");
        if (!SELF_MANAGED.contains(providerType)) {
            throw new AwsException("UnsupportedProviderTypeException",
                    "ProviderType " + providerType + " is not supported for hosts.", 400);
        }
        for (CodeConnectionsHost existing : hosts.values()) {
            if (name.equals(existing.getName()) && region.equals(existing.getRegion())) {
                throw new AwsException("LimitExceededException",
                        "A host with name " + name + " already exists.", 429);
            }
        }
        String id = UUID.randomUUID().toString();
        String arn = regionResolver.buildArn(SERVICE, region, "host/" + id);
        CodeConnectionsHost host = new CodeConnectionsHost();
        host.setName(name);
        host.setHostArn(arn);
        host.setProviderType(providerType);
        host.setProviderEndpoint(providerEndpoint);
        host.setStatus(STATUS_PENDING);
        host.setRegion(region);
        host.setAccountId(regionResolver.getAccountId());
        host.setVpcConfiguration(vpc);
        host.setTags(copyTags(tags));
        hosts.put(arn, host);
        return host;
    }

    public CodeConnectionsHost getHost(String hostArn) {
        require(hostArn, "HostArn");
        return hosts.get(hostArn).orElseThrow(() -> notFound("Host", hostArn));
    }

    public void updateHost(String hostArn, String providerEndpoint, CodeConnectionsVpcConfiguration vpc) {
        CodeConnectionsHost host = getHost(hostArn);
        if (providerEndpoint != null && !providerEndpoint.isBlank()) {
            host.setProviderEndpoint(providerEndpoint);
        }
        if (vpc != null) {
            host.setVpcConfiguration(vpc);
        }
        hosts.put(host.getHostArn(), host);
    }

    public void deleteHost(String hostArn) {
        CodeConnectionsHost host = getHost(hostArn);
        for (CodeConnectionsConnection connection : connections.values()) {
            if (hostArn.equals(connection.getHostArn())) {
                throw new AwsException("ResourceUnavailableException",
                        "Cannot delete the host because it is associated with a connection.", 400);
            }
        }
        hosts.delete(host.getHostArn());
    }

    public Page<CodeConnectionsHost> listHosts(String region, String nextToken, Integer maxResults) {
        List<CodeConnectionsHost> result = new ArrayList<>();
        for (CodeConnectionsHost host : hosts.values()) {
            if (region == null || region.equals(host.getRegion())) {
                result.add(host);
            }
        }
        result.sort(Comparator.comparing(CodeConnectionsHost::getHostArn, Comparator.nullsLast(String::compareTo)));
        return page(result, nextToken, maxResults);
    }

    public CodeConnectionsConnection createConnection(String region, String name, String providerType,
                                                      String hostArn, Map<String, String> tags) {
        require(name, "ConnectionName");
        if ((providerType == null || providerType.isBlank()) && (hostArn == null || hostArn.isBlank())) {
            throw invalid("ProviderType or HostArn is required.");
        }
        if (hostArn != null && !hostArn.isBlank()) {
            CodeConnectionsHost host = getHost(hostArn);
            if (providerType == null || providerType.isBlank()) {
                providerType = host.getProviderType();
            }
        }
        String id = UUID.randomUUID().toString();
        String arn = regionResolver.buildArn(SERVICE, region, "connection/" + id);
        CodeConnectionsConnection connection = new CodeConnectionsConnection();
        connection.setConnectionArn(arn);
        connection.setConnectionName(name);
        connection.setProviderType(providerType == null || providerType.isBlank() ? DEFAULT_PROVIDER : providerType);
        connection.setOwnerAccountId(regionResolver.getAccountId());
        connection.setConnectionStatus(STATUS_PENDING);
        connection.setHostArn(blankToNull(hostArn));
        connection.setRegion(region);
        connection.setTags(copyTags(tags));
        connections.put(arn, connection);
        return connection;
    }

    public CodeConnectionsConnection getConnection(String connectionArn) {
        require(connectionArn, "ConnectionArn");
        return connections.get(connectionArn).orElseThrow(() -> notFound("Connection", connectionArn));
    }

    public void deleteConnection(String connectionArn) {
        getConnection(connectionArn);
        connections.delete(connectionArn);
    }

    public Page<CodeConnectionsConnection> listConnections(String region, String providerTypeFilter,
                                                           String hostArnFilter, String nextToken,
                                                           Integer maxResults) {
        List<CodeConnectionsConnection> result = new ArrayList<>();
        for (CodeConnectionsConnection connection : connections.values()) {
            if (region != null && !region.equals(connection.getRegion())) {
                continue;
            }
            if (providerTypeFilter != null && !providerTypeFilter.equals(connection.getProviderType())) {
                continue;
            }
            if (hostArnFilter != null && !hostArnFilter.equals(connection.getHostArn())) {
                continue;
            }
            result.add(connection);
        }
        result.sort(Comparator.comparing(CodeConnectionsConnection::getConnectionArn));
        return page(result, nextToken, maxResults);
    }

    public CodeConnectionsRepositoryLink createRepositoryLink(String region, String connectionArn, String ownerId,
                                                              String repositoryName, String encryptionKeyArn,
                                                              Map<String, String> tags) {
        require(connectionArn, "ConnectionArn");
        require(ownerId, "OwnerId");
        require(repositoryName, "RepositoryName");
        CodeConnectionsConnection connection = getConnection(connectionArn);
        for (CodeConnectionsRepositoryLink existing : links.values()) {
            if (ownerId.equals(existing.getOwnerId()) && repositoryName.equals(existing.getRepositoryName())) {
                throw new AwsException("ResourceAlreadyExistsException",
                        "A repository link already exists for " + ownerId + "/" + repositoryName + ".", 409);
            }
        }
        String id = UUID.randomUUID().toString();
        String arn = regionResolver.buildArn(SERVICE, region, "repository-link/" + id);
        CodeConnectionsRepositoryLink link = new CodeConnectionsRepositoryLink();
        link.setRepositoryLinkId(id);
        link.setRepositoryLinkArn(arn);
        link.setConnectionArn(connectionArn);
        link.setOwnerId(ownerId);
        link.setRepositoryName(repositoryName);
        link.setProviderType(connection.getProviderType());
        link.setEncryptionKeyArn(blankToNull(encryptionKeyArn));
        link.setRegion(region);
        link.setTags(copyTags(tags));
        links.put(id, link);
        return link;
    }

    public CodeConnectionsRepositoryLink getRepositoryLink(String repositoryLinkId) {
        require(repositoryLinkId, "RepositoryLinkId");
        return links.get(repositoryLinkId).orElseThrow(() -> notFound("Repository link", repositoryLinkId));
    }

    public CodeConnectionsRepositoryLink updateRepositoryLink(String repositoryLinkId, String connectionArn,
                                                              String encryptionKeyArn) {
        CodeConnectionsRepositoryLink link = getRepositoryLink(repositoryLinkId);
        if (connectionArn != null && !connectionArn.isBlank()) {
            CodeConnectionsConnection connection = getConnection(connectionArn);
            link.setConnectionArn(connectionArn);
            link.setProviderType(connection.getProviderType());
        }
        if (encryptionKeyArn != null) {
            link.setEncryptionKeyArn(blankToNull(encryptionKeyArn));
        }
        links.put(link.getRepositoryLinkId(), link);
        return link;
    }

    public void deleteRepositoryLink(String repositoryLinkId) {
        getRepositoryLink(repositoryLinkId);
        for (CodeConnectionsSyncConfiguration sync : syncs.values()) {
            if (repositoryLinkId.equals(sync.getRepositoryLinkId())) {
                throw new AwsException("SyncConfigurationStillExistsException",
                        "Cannot delete repository link while sync configurations exist.", 409);
            }
        }
        links.delete(repositoryLinkId);
    }

    public Page<CodeConnectionsRepositoryLink> listRepositoryLinks(String region, String nextToken,
                                                                   Integer maxResults) {
        List<CodeConnectionsRepositoryLink> result = new ArrayList<>();
        for (CodeConnectionsRepositoryLink link : links.values()) {
            if (region == null || region.equals(link.getRegion())) {
                result.add(link);
            }
        }
        result.sort(Comparator.comparing(CodeConnectionsRepositoryLink::getRepositoryLinkId));
        return page(result, nextToken, maxResults);
    }

    public CodeConnectionsSyncConfiguration createSyncConfiguration(
            String region, String branch, String configFile, String repositoryLinkId, String resourceName,
            String roleArn, String syncType, String publishDeploymentStatus, String triggerResourceUpdateOn,
            String pullRequestComment) {
        require(branch, "Branch");
        require(configFile, "ConfigFile");
        require(repositoryLinkId, "RepositoryLinkId");
        require(resourceName, "ResourceName");
        require(roleArn, "RoleArn");
        String type = requireSyncType(syncType);
        CodeConnectionsRepositoryLink link = getRepositoryLink(repositoryLinkId);
        String key = syncKey(type, resourceName);
        if (syncs.get(key).isPresent()) {
            throw new AwsException("ResourceAlreadyExistsException",
                    "Sync configuration already exists for " + type + "/" + resourceName, 409);
        }
        CodeConnectionsSyncConfiguration config = new CodeConnectionsSyncConfiguration();
        config.setBranch(branch);
        config.setConfigFile(configFile);
        config.setOwnerId(link.getOwnerId());
        config.setProviderType(link.getProviderType());
        config.setRepositoryLinkId(repositoryLinkId);
        config.setRepositoryName(link.getRepositoryName());
        config.setResourceName(resourceName);
        config.setRoleArn(roleArn);
        config.setSyncType(type);
        config.setPublishDeploymentStatus(optionalEnum(publishDeploymentStatus, PUBLISH_STATUS,
                "PublishDeploymentStatus", "ENABLED"));
        config.setTriggerResourceUpdateOn(optionalEnum(triggerResourceUpdateOn, TRIGGER_ON,
                "TriggerResourceUpdateOn", "ANY_CHANGE"));
        config.setPullRequestComment(optionalEnum(pullRequestComment, PR_COMMENT,
                "PullRequestComment", "ENABLED"));
        config.setRegion(region);
        syncs.put(key, config);
        return config;
    }

    public CodeConnectionsSyncConfiguration getSyncConfiguration(String syncType, String resourceName) {
        require(resourceName, "ResourceName");
        String type = requireSyncType(syncType);
        return syncs.get(syncKey(type, resourceName))
                .orElseThrow(() -> notFound("Sync configuration", type + "/" + resourceName));
    }

    public CodeConnectionsSyncConfiguration updateSyncConfiguration(
            String region, String syncType, String resourceName, String branch, String configFile,
            String repositoryLinkId, String roleArn, String publishDeploymentStatus,
            String triggerResourceUpdateOn, String pullRequestComment) {
        CodeConnectionsSyncConfiguration config = getSyncConfiguration(syncType, resourceName);
        if (branch != null && !branch.isBlank()) {
            config.setBranch(branch);
        }
        if (configFile != null && !configFile.isBlank()) {
            config.setConfigFile(configFile);
        }
        if (repositoryLinkId != null && !repositoryLinkId.isBlank()) {
            CodeConnectionsRepositoryLink link = getRepositoryLink(repositoryLinkId);
            config.setRepositoryLinkId(repositoryLinkId);
            config.setOwnerId(link.getOwnerId());
            config.setProviderType(link.getProviderType());
            config.setRepositoryName(link.getRepositoryName());
        }
        if (roleArn != null && !roleArn.isBlank()) {
            config.setRoleArn(roleArn);
        }
        if (publishDeploymentStatus != null) {
            config.setPublishDeploymentStatus(optionalEnum(publishDeploymentStatus, PUBLISH_STATUS,
                    "PublishDeploymentStatus", config.getPublishDeploymentStatus()));
        }
        if (triggerResourceUpdateOn != null) {
            config.setTriggerResourceUpdateOn(optionalEnum(triggerResourceUpdateOn, TRIGGER_ON,
                    "TriggerResourceUpdateOn", config.getTriggerResourceUpdateOn()));
        }
        if (pullRequestComment != null) {
            config.setPullRequestComment(optionalEnum(pullRequestComment, PR_COMMENT,
                    "PullRequestComment", config.getPullRequestComment()));
        }
        if (region != null && !region.isBlank()) {
            config.setRegion(region);
        }
        syncs.put(syncKey(config.getSyncType(), config.getResourceName()), config);
        return config;
    }

    public void deleteSyncConfiguration(String syncType, String resourceName) {
        require(resourceName, "ResourceName");
        String type = requireSyncType(syncType);
        String key = syncKey(type, resourceName);
        if (syncs.get(key).isEmpty()) {
            throw invalid("Sync configuration " + type + "/" + resourceName + " does not exist.");
        }
        syncs.delete(key);
    }

    public Page<CodeConnectionsSyncConfiguration> listSyncConfigurations(
            String region, String repositoryLinkId, String syncType, String nextToken, Integer maxResults) {
        require(repositoryLinkId, "RepositoryLinkId");
        getRepositoryLink(repositoryLinkId);
        String type = requireSyncType(syncType);
        List<CodeConnectionsSyncConfiguration> result = new ArrayList<>();
        for (CodeConnectionsSyncConfiguration config : syncs.values()) {
            if (repositoryLinkId.equals(config.getRepositoryLinkId())
                    && type.equals(config.getSyncType())
                    && (region == null || region.equals(config.getRegion()))) {
                result.add(config);
            }
        }
        result.sort(Comparator.comparing(CodeConnectionsSyncConfiguration::getResourceName));
        return page(result, nextToken, maxResults);
    }

    public Map<String, String> listTags(String resourceArn) {
        return new LinkedHashMap<>(taggedResource(resourceArn).tags());
    }

    public void tagResource(String resourceArn, Map<String, String> tags) {
        Tagged tagged = taggedResource(resourceArn);
        tagged.tags().putAll(copyTags(tags));
        tagged.writer().run();
    }

    public void untagResource(String resourceArn, List<String> keys) {
        Tagged tagged = taggedResource(resourceArn);
        if (keys != null) {
            keys.forEach(tagged.tags()::remove);
        }
        tagged.writer().run();
    }

    static int pageSize(Integer maxResults) {
        if (maxResults == null || maxResults <= 0) {
            return DEFAULT_MAX_RESULTS;
        }
        return Math.min(maxResults, DEFAULT_MAX_RESULTS);
    }

    static int pageOffset(String nextToken) {
        if (nextToken == null || nextToken.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(nextToken));
        } catch (NumberFormatException e) {
            throw invalid("Invalid NextToken.");
        }
    }

    private Tagged taggedResource(String resourceArn) {
        require(resourceArn, "ResourceArn");
        if (resourceArn.contains(":connection/")) {
            CodeConnectionsConnection connection = getConnection(resourceArn);
            return new Tagged(connection.getTags(), () -> connections.put(connection.getConnectionArn(), connection));
        }
        if (resourceArn.contains(":host/")) {
            CodeConnectionsHost host = getHost(resourceArn);
            return new Tagged(host.getTags(), () -> hosts.put(host.getHostArn(), host));
        }
        if (resourceArn.contains(":repository-link/")) {
            String id = resourceArn.substring(resourceArn.lastIndexOf('/') + 1);
            CodeConnectionsRepositoryLink link = getRepositoryLink(id);
            return new Tagged(link.getTags(), () -> links.put(link.getRepositoryLinkId(), link));
        }
        throw notFound("Resource", resourceArn);
    }

    private static String syncKey(String syncType, String resourceName) {
        return syncType + ":" + resourceName;
    }

    private static String requireSyncType(String syncType) {
        require(syncType, "SyncType");
        return syncType;
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw invalid(field + " is required.");
        }
    }

    private static String optionalEnum(String value, Set<String> allowed, String field, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        if (!allowed.contains(value)) {
            throw invalid(field + " must be one of " + allowed + ".");
        }
        return value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static Map<String, String> copyTags(Map<String, String> tags) {
        return tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }

    private static AwsException notFound(String kind, String id) {
        return new AwsException("ResourceNotFoundException", kind + " " + id + " not found.", 404);
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidInputException", message, 400);
    }

    private static <T> Page<T> page(List<T> all, String nextToken, Integer maxResults) {
        int start = pageOffset(nextToken);
        if (start > all.size()) {
            throw invalid("Invalid NextToken.");
        }
        int limit = pageSize(maxResults);
        int end = Math.min(all.size(), start + limit);
        String token = end < all.size() ? Integer.toString(end) : null;
        return new Page<>(new ArrayList<>(all.subList(start, end)), token);
    }

    public record Page<T>(List<T> items, String nextToken) {
    }

    private record Tagged(Map<String, String> tags, Runnable writer) {
    }
}
