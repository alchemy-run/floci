package io.github.hectorvent.floci.services.grafana;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.grafana.model.GrafanaWorkspace;
import io.github.hectorvent.floci.services.grafana.model.GrafanaWorkspace.PermissionEntry;
import io.github.hectorvent.floci.services.grafana.model.GrafanaWorkspace.ServiceAccount;
import io.github.hectorvent.floci.services.grafana.model.GrafanaWorkspace.Token;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Amazon Managed Grafana restJson1 — workspace lifecycle, versions, auth,
 * configuration, permissions, licenses, and service-account tokens.
 */
@ApplicationScoped
public class GrafanaService implements TagHandler {

    static final String SERVICE = "grafana";
    static final List<String> AVAILABLE_VERSIONS = List.of("8.4", "9.4", "10.4");
    static final String DEFAULT_VERSION = "10.4";
    static final String DEFAULT_CONFIGURATION = "{\"unifiedAlerting\":{\"enabled\":true}}";

    private static final int DEFAULT_MAX_RESULTS = 100;
    private static final int MAX_RESULTS = 100;
    private static final String TOKEN_PREFIX = "grafana:v1:";
    private static final String DEFAULT_CONFIG = DEFAULT_CONFIGURATION;
    private static final Set<String> ACCOUNT_ACCESS = Set.of("CURRENT_ACCOUNT", "ORGANIZATION");
    private static final Set<String> PERMISSION_TYPES = Set.of("SERVICE_MANAGED", "CUSTOMER_MANAGED");
    private static final Set<String> AUTH_PROVIDERS = Set.of("AWS_SSO", "SAML");
    private static final Set<String> GRAFANA_ROLES = Set.of("ADMIN", "EDITOR", "VIEWER");
    private static final Set<String> LICENSE_TYPES = Set.of("ENTERPRISE", "ENTERPRISE_FREE_TRIAL");
    private static final int MAX_TOKEN_TTL = 2_592_000;

    private final StorageBackend<String, GrafanaWorkspace> store;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public GrafanaService(StorageFactory storageFactory, RegionResolver regionResolver, ObjectMapper objectMapper) {
        this(storageFactory.create(
                "grafana",
                "grafana-workspaces.json",
                new TypeReference<Map<String, GrafanaWorkspace>>() {
                }), regionResolver, objectMapper);
    }

    GrafanaService(
            StorageBackend<String, GrafanaWorkspace> store,
            RegionResolver regionResolver,
            ObjectMapper objectMapper) {
        this.store = store;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    public ObjectNode listVersions(String region, String maxResults, String nextToken, String workspaceId) {
        List<String> versions = new ArrayList<>(AVAILABLE_VERSIONS);
        if (workspaceId != null && !workspaceId.isBlank()) {
            GrafanaWorkspace workspace = requireWorkspace(region, workspaceId);
            versions.removeIf(version -> compareVersions(version, workspace.getGrafanaVersion()) <= 0);
        }
        versions.sort(GrafanaService::compareVersions);
        return paginateStrings(versions, maxResults, nextToken, "grafanaVersions");
    }

    public synchronized GrafanaWorkspace createWorkspace(String region, JsonNode request) {
        requireObject(request, "Request body");
        String accountAccessType = requireEnum(request, "accountAccessType", ACCOUNT_ACCESS);
        String permissionType = requireEnum(request, "permissionType", PERMISSION_TYPES);
        List<String> providers = requireStringList(request, "authenticationProviders");
        if (providers.isEmpty()) {
            throw validation("authenticationProviders must contain at least one provider.");
        }
        for (String provider : providers) {
            if (!AUTH_PROVIDERS.contains(provider)) {
                throw validation("authenticationProviders member is invalid: " + provider);
            }
        }
        String roleArn = textOrNull(request, "workspaceRoleArn");
        if ("CUSTOMER_MANAGED".equals(permissionType) && (roleArn == null || roleArn.isBlank())) {
            throw validation("workspaceRoleArn is required when permissionType is CUSTOMER_MANAGED.");
        }
        String version = textOrNull(request, "grafanaVersion");
        if (version == null || version.isBlank()) {
            version = DEFAULT_VERSION;
        } else if (!AVAILABLE_VERSIONS.contains(version)) {
            throw validation("grafanaVersion is not a supported Grafana version: " + version);
        }

        long now = Instant.now().getEpochSecond();
        String id = newWorkspaceId();
        GrafanaWorkspace workspace = new GrafanaWorkspace();
        workspace.setId(id);
        workspace.setRegion(region);
        workspace.setAccountId(regionResolver.getAccountId());
        workspace.setName(textOrNull(request, "workspaceName"));
        workspace.setDescription(textOrNull(request, "workspaceDescription"));
        workspace.setAccountAccessType(accountAccessType);
        workspace.setPermissionType(permissionType);
        workspace.setAuthenticationProviders(providers);
        workspace.setDataSources(optionalStringList(request, "workspaceDataSources"));
        workspace.setGrafanaVersion(version);
        workspace.setWorkspaceRoleArn(roleArn);
        workspace.setStatus("ACTIVE");
        workspace.setCreated(now);
        workspace.setModified(now);
        workspace.setTags(readTags(request));
        String configuration = textOrNull(request, "configuration");
        workspace.setConfiguration(configuration == null || configuration.isBlank() ? DEFAULT_CONFIG : configuration);
        workspace.setSamlStatus(providers.contains("SAML") ? "NOT_CONFIGURED" : null);
        store.put(storageKey(region, id), workspace);
        return workspace;
    }

    public GrafanaWorkspace describeWorkspace(String region, String workspaceId) {
        return requireWorkspace(region, workspaceId);
    }

    public List<GrafanaWorkspace> listWorkspaces(String region) {
        List<GrafanaWorkspace> workspaces = store.scan(key -> key.startsWith(region + "::"));
        workspaces.sort(Comparator.comparing(GrafanaWorkspace::getId));
        return workspaces;
    }

    public ObjectNode listWorkspacesPage(String region, String maxResults, String nextToken) {
        List<GrafanaWorkspace> workspaces = listWorkspaces(region);
        Page<GrafanaWorkspace> page = paginate(workspaces, maxResults, nextToken);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("workspaces");
        for (GrafanaWorkspace workspace : page.items()) {
            list.add(toSummary(workspace));
        }
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return response;
    }

    public synchronized GrafanaWorkspace updateWorkspace(String region, String workspaceId, JsonNode request) {
        GrafanaWorkspace workspace = requireWorkspace(region, workspaceId);
        requireObject(request, "Request body");
        if (request.has("workspaceName")) {
            workspace.setName(textOrNull(request, "workspaceName"));
        }
        if (request.has("workspaceDescription")) {
            workspace.setDescription(textOrNull(request, "workspaceDescription"));
        }
        if (request.has("workspaceDataSources")) {
            workspace.setDataSources(optionalStringList(request, "workspaceDataSources"));
        }
        if (request.has("workspaceRoleArn")) {
            workspace.setWorkspaceRoleArn(textOrNull(request, "workspaceRoleArn"));
        }
        if (request.has("accountAccessType")) {
            workspace.setAccountAccessType(requireEnum(request, "accountAccessType", ACCOUNT_ACCESS));
        }
        if (request.has("permissionType")) {
            workspace.setPermissionType(requireEnum(request, "permissionType", PERMISSION_TYPES));
        }
        workspace.setModified(Instant.now().getEpochSecond());
        persist(workspace);
        return workspace;
    }

    public synchronized GrafanaWorkspace deleteWorkspace(String region, String workspaceId) {
        GrafanaWorkspace workspace = requireWorkspace(region, workspaceId);
        workspace.setStatus("DELETING");
        workspace.setModified(Instant.now().getEpochSecond());
        store.delete(storageKey(region, workspaceId));
        return workspace;
    }

    public ObjectNode describeAuthentication(String region, String workspaceId) {
        GrafanaWorkspace workspace = requireWorkspace(region, workspaceId);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("authentication", authenticationDescription(workspace));
        return response;
    }

    public synchronized ObjectNode updateAuthentication(String region, String workspaceId, JsonNode request) {
        GrafanaWorkspace workspace = requireWorkspace(region, workspaceId);
        requireObject(request, "Request body");
        List<String> providers = requireStringList(request, "authenticationProviders");
        if (providers.isEmpty()) {
            throw validation("authenticationProviders must contain at least one provider.");
        }
        for (String provider : providers) {
            if (!AUTH_PROVIDERS.contains(provider)) {
                throw validation("authenticationProviders member is invalid: " + provider);
            }
        }
        workspace.setAuthenticationProviders(providers);
        if (request.has("samlConfiguration") && !request.get("samlConfiguration").isNull()) {
            JsonNode saml = request.get("samlConfiguration");
            requireObject(saml, "samlConfiguration");
            workspace.setSamlConfiguration(saml.deepCopy());
            workspace.setSamlStatus("CONFIGURED");
        } else if (providers.contains("SAML")) {
            workspace.setSamlStatus(workspace.getSamlStatus() == null ? "NOT_CONFIGURED" : workspace.getSamlStatus());
        } else {
            workspace.setSamlStatus(null);
            workspace.setSamlConfiguration(null);
        }
        workspace.setModified(Instant.now().getEpochSecond());
        persist(workspace);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("authentication", authenticationDescription(workspace));
        return response;
    }

    public ObjectNode describeConfiguration(String region, String workspaceId) {
        GrafanaWorkspace workspace = requireWorkspace(region, workspaceId);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("configuration", workspace.getConfiguration() == null ? DEFAULT_CONFIG : workspace.getConfiguration());
        response.put("grafanaVersion", workspace.getGrafanaVersion());
        return response;
    }

    public synchronized void updateConfiguration(String region, String workspaceId, JsonNode request) {
        GrafanaWorkspace workspace = requireWorkspace(region, workspaceId);
        requireObject(request, "Request body");
        String configuration = requireText(request, "configuration");
        workspace.setConfiguration(configuration);
        if (request.has("grafanaVersion") && !request.get("grafanaVersion").isNull()) {
            String version = requireText(request, "grafanaVersion");
            if (!AVAILABLE_VERSIONS.contains(version)) {
                throw validation("grafanaVersion is not a supported Grafana version: " + version);
            }
            workspace.setGrafanaVersion(version);
        }
        workspace.setModified(Instant.now().getEpochSecond());
        persist(workspace);
    }

    public synchronized GrafanaWorkspace associateLicense(
            String region, String workspaceId, String licenseType, String grafanaToken) {
        GrafanaWorkspace workspace = requireWorkspace(region, workspaceId);
        if (licenseType == null || !LICENSE_TYPES.contains(licenseType)) {
            throw validation("licenseType must be ENTERPRISE or ENTERPRISE_FREE_TRIAL.");
        }
        if ("ENTERPRISE".equals(licenseType) && (grafanaToken == null || grafanaToken.isBlank())) {
            throw validation("Grafana-Token is required when associating an ENTERPRISE license.");
        }
        workspace.setLicenseType(licenseType);
        workspace.setGrafanaToken(grafanaToken);
        workspace.setModified(Instant.now().getEpochSecond());
        persist(workspace);
        return workspace;
    }

    public synchronized GrafanaWorkspace disassociateLicense(String region, String workspaceId, String licenseType) {
        GrafanaWorkspace workspace = requireWorkspace(region, workspaceId);
        if (licenseType == null || licenseType.isBlank()) {
            throw validation("licenseType is required.");
        }
        workspace.setLicenseType(null);
        workspace.setGrafanaToken(null);
        workspace.setModified(Instant.now().getEpochSecond());
        persist(workspace);
        return workspace;
    }

    public ObjectNode listPermissions(String region, String workspaceId) {
        GrafanaWorkspace workspace = requireWorkspace(region, workspaceId);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode permissions = response.putArray("permissions");
        for (PermissionEntry entry : workspace.getPermissions()) {
            ObjectNode item = permissions.addObject();
            ObjectNode user = item.putObject("user");
            user.put("id", entry.getUserId());
            user.put("type", entry.getUserType());
            item.put("role", entry.getRole());
        }
        return response;
    }

    public synchronized ObjectNode updatePermissions(String region, String workspaceId, JsonNode request) {
        GrafanaWorkspace workspace = requireWorkspace(region, workspaceId);
        requireObject(request, "Request body");
        JsonNode batch = request.get("updateInstructionBatch");
        if (batch == null || !batch.isArray()) {
            throw validation("updateInstructionBatch is required.");
        }
        ArrayNode errors = objectMapper.createArrayNode();
        for (JsonNode instruction : batch) {
            if (instruction == null || !instruction.isObject()) {
                throw validation("updateInstructionBatch members must be objects.");
            }
            String action = requireText(instruction, "action");
            String role = requireText(instruction, "role");
            if (!GRAFANA_ROLES.contains(role)) {
                throw validation("role must be ADMIN, EDITOR, or VIEWER.");
            }
            JsonNode users = instruction.get("users");
            if (users == null || !users.isArray() || users.isEmpty()) {
                throw validation("users is required.");
            }
            for (JsonNode user : users) {
                requireObject(user, "user");
                String userId = requireText(user, "id");
                String userType = requireText(user, "type");
                if ("ADD".equals(action)) {
                    workspace.getPermissions().removeIf(
                            entry -> userId.equals(entry.getUserId()) && userType.equals(entry.getUserType()));
                    workspace.getPermissions().add(new PermissionEntry(userId, userType, role));
                } else if ("REVOKE".equals(action)) {
                    workspace.getPermissions().removeIf(
                            entry -> userId.equals(entry.getUserId()) && userType.equals(entry.getUserType()));
                } else {
                    throw validation("action must be ADD or REVOKE.");
                }
            }
        }
        workspace.setModified(Instant.now().getEpochSecond());
        persist(workspace);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("errors", errors);
        return response;
    }

    public synchronized ObjectNode createServiceAccount(String region, String workspaceId, JsonNode request) {
        GrafanaWorkspace workspace = requireWorkspace(region, workspaceId);
        requireObject(request, "Request body");
        String name = requireText(request, "name");
        String role = requireEnum(request, "grafanaRole", GRAFANA_ROLES);
        for (ServiceAccount existing : workspace.getServiceAccounts().values()) {
            if (name.equals(existing.getName())) {
                throw conflict(workspaceId, "ServiceAccount");
            }
        }
        ServiceAccount account = new ServiceAccount();
        account.setId(newShortId());
        account.setName(name);
        account.setGrafanaRole(role);
        account.setIsDisabled("false");
        workspace.getServiceAccounts().put(account.getId(), account);
        workspace.setModified(Instant.now().getEpochSecond());
        persist(workspace);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("id", account.getId());
        response.put("name", account.getName());
        response.put("grafanaRole", account.getGrafanaRole());
        response.put("workspaceId", workspaceId);
        return response;
    }

    public synchronized ObjectNode deleteServiceAccount(String region, String workspaceId, String serviceAccountId) {
        GrafanaWorkspace workspace = requireWorkspace(region, workspaceId);
        ServiceAccount account = workspace.getServiceAccounts().remove(serviceAccountId);
        if (account == null) {
            throw notFound(serviceAccountId, "ServiceAccount");
        }
        workspace.setModified(Instant.now().getEpochSecond());
        persist(workspace);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("serviceAccountId", serviceAccountId);
        response.put("workspaceId", workspaceId);
        return response;
    }

    public ObjectNode listServiceAccounts(String region, String workspaceId) {
        GrafanaWorkspace workspace = requireWorkspace(region, workspaceId);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode accounts = response.putArray("serviceAccounts");
        for (ServiceAccount account : workspace.getServiceAccounts().values()) {
            ObjectNode item = accounts.addObject();
            item.put("id", account.getId());
            item.put("name", account.getName());
            item.put("isDisabled", account.getIsDisabled());
            item.put("grafanaRole", account.getGrafanaRole());
        }
        response.put("workspaceId", workspaceId);
        return response;
    }

    public synchronized ObjectNode createServiceAccountToken(
            String region, String workspaceId, String serviceAccountId, JsonNode request) {
        GrafanaWorkspace workspace = requireWorkspace(region, workspaceId);
        ServiceAccount account = requireServiceAccount(workspace, serviceAccountId);
        requireObject(request, "Request body");
        String name = requireText(request, "name");
        int secondsToLive = requireInt(request, "secondsToLive");
        if (secondsToLive < 1 || secondsToLive > MAX_TOKEN_TTL) {
            throw validation("secondsToLive must be between 1 and " + MAX_TOKEN_TTL + ".");
        }
        for (Token existing : account.getTokens().values()) {
            if (name.equals(existing.getName())) {
                throw conflict(serviceAccountId, "ServiceAccountToken");
            }
        }
        long now = Instant.now().getEpochSecond();
        Token token = new Token();
        token.setId(newShortId());
        token.setName(name);
        token.setCreatedAt(now);
        token.setExpiresAt(now + secondsToLive);
        token.setKey(newGrafanaTokenKey());
        account.getTokens().put(token.getId(), token);
        workspace.setModified(now);
        persist(workspace);
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode summary = response.putObject("serviceAccountToken");
        summary.put("id", token.getId());
        summary.put("name", token.getName());
        summary.put("key", token.getKey());
        response.put("serviceAccountId", serviceAccountId);
        response.put("workspaceId", workspaceId);
        return response;
    }

    public synchronized ObjectNode deleteServiceAccountToken(
            String region, String workspaceId, String serviceAccountId, String tokenId) {
        GrafanaWorkspace workspace = requireWorkspace(region, workspaceId);
        ServiceAccount account = requireServiceAccount(workspace, serviceAccountId);
        Token token = account.getTokens().remove(tokenId);
        if (token == null) {
            throw notFound(tokenId, "ServiceAccountToken");
        }
        workspace.setModified(Instant.now().getEpochSecond());
        persist(workspace);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("tokenId", tokenId);
        response.put("serviceAccountId", serviceAccountId);
        response.put("workspaceId", workspaceId);
        return response;
    }

    public ObjectNode listServiceAccountTokens(String region, String workspaceId, String serviceAccountId) {
        GrafanaWorkspace workspace = requireWorkspace(region, workspaceId);
        ServiceAccount account = requireServiceAccount(workspace, serviceAccountId);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode tokens = response.putArray("serviceAccountTokens");
        for (Token token : account.getTokens().values()) {
            ObjectNode item = tokens.addObject();
            item.put("id", token.getId());
            item.put("name", token.getName());
            item.put("createdAt", token.getCreatedAt());
            item.put("expiresAt", token.getExpiresAt());
        }
        response.put("serviceAccountId", serviceAccountId);
        response.put("workspaceId", workspaceId);
        return response;
    }

    @Override
    public String serviceKey() {
        return SERVICE;
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        return new LinkedHashMap<>(requireWorkspaceByArn(region, arn).getTags());
    }

    @Override
    public synchronized void tagResource(String region, String arn, Map<String, String> tags) {
        GrafanaWorkspace workspace = requireWorkspaceByArn(region, arn);
        if (tags != null) {
            workspace.getTags().putAll(tags);
            workspace.setModified(Instant.now().getEpochSecond());
            persist(workspace);
        }
    }

    @Override
    public synchronized void untagResource(String region, String arn, List<String> tagKeys) {
        GrafanaWorkspace workspace = requireWorkspaceByArn(region, arn);
        if (tagKeys != null) {
            for (String key : tagKeys) {
                workspace.getTags().remove(key);
            }
            workspace.setModified(Instant.now().getEpochSecond());
            persist(workspace);
        }
    }

    public ObjectNode toDescription(GrafanaWorkspace workspace) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", workspace.getId());
        node.put("status", workspace.getStatus());
        node.put("grafanaVersion", workspace.getGrafanaVersion());
        node.put("endpoint", endpoint(workspace));
        node.put("created", workspace.getCreated());
        node.put("modified", workspace.getModified());
        if (workspace.getAccountAccessType() != null) {
            node.put("accountAccessType", workspace.getAccountAccessType());
        }
        if (workspace.getPermissionType() != null) {
            node.put("permissionType", workspace.getPermissionType());
        }
        if (workspace.getName() != null) {
            node.put("name", workspace.getName());
        }
        if (workspace.getDescription() != null) {
            node.put("description", workspace.getDescription());
        }
        if (workspace.getWorkspaceRoleArn() != null) {
            node.put("workspaceRoleArn", workspace.getWorkspaceRoleArn());
        }
        if (workspace.getLicenseType() != null) {
            node.put("licenseType", workspace.getLicenseType());
        }
        if (workspace.getGrafanaToken() != null) {
            node.put("grafanaToken", workspace.getGrafanaToken());
        }
        ArrayNode dataSources = node.putArray("dataSources");
        for (String dataSource : workspace.getDataSources()) {
            dataSources.add(dataSource);
        }
        node.set("authentication", authenticationSummary(workspace));
        if (!workspace.getTags().isEmpty()) {
            ObjectNode tags = node.putObject("tags");
            workspace.getTags().forEach(tags::put);
        }
        return node;
    }

    public ObjectNode workspaceResponse(GrafanaWorkspace workspace) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("workspace", toDescription(workspace));
        return response;
    }

    private ObjectNode toSummary(GrafanaWorkspace workspace) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", workspace.getId());
        node.put("status", workspace.getStatus());
        node.put("grafanaVersion", workspace.getGrafanaVersion());
        node.put("endpoint", endpoint(workspace));
        node.put("created", workspace.getCreated());
        node.put("modified", workspace.getModified());
        if (workspace.getName() != null) {
            node.put("name", workspace.getName());
        }
        if (workspace.getDescription() != null) {
            node.put("description", workspace.getDescription());
        }
        if (workspace.getLicenseType() != null) {
            node.put("licenseType", workspace.getLicenseType());
        }
        node.set("authentication", authenticationSummary(workspace));
        if (!workspace.getTags().isEmpty()) {
            ObjectNode tags = node.putObject("tags");
            workspace.getTags().forEach(tags::put);
        }
        return node;
    }

    private ObjectNode authenticationSummary(GrafanaWorkspace workspace) {
        ObjectNode authentication = objectMapper.createObjectNode();
        ArrayNode providers = authentication.putArray("providers");
        for (String provider : workspace.getAuthenticationProviders()) {
            providers.add(provider);
        }
        if (workspace.getSamlStatus() != null) {
            authentication.put("samlConfigurationStatus", workspace.getSamlStatus());
        }
        return authentication;
    }

    private ObjectNode authenticationDescription(GrafanaWorkspace workspace) {
        ObjectNode authentication = objectMapper.createObjectNode();
        ArrayNode providers = authentication.putArray("providers");
        for (String provider : workspace.getAuthenticationProviders()) {
            providers.add(provider);
        }
        if (workspace.getAuthenticationProviders().contains("SAML")) {
            ObjectNode saml = authentication.putObject("saml");
            saml.put("status", workspace.getSamlStatus() == null ? "NOT_CONFIGURED" : workspace.getSamlStatus());
            if (workspace.getSamlConfiguration() != null) {
                saml.set("configuration", workspace.getSamlConfiguration());
            }
        }
        if (workspace.getAuthenticationProviders().contains("AWS_SSO")) {
            ObjectNode sso = authentication.putObject("awsSso");
            sso.put("ssoClientId", "floci-grafana-" + workspace.getId());
        }
        return authentication;
    }

    private String endpoint(GrafanaWorkspace workspace) {
        return "https://" + workspace.getId() + ".grafana-workspace." + workspace.getRegion() + ".amazonaws.com";
    }

    private GrafanaWorkspace requireWorkspace(String region, String workspaceId) {
        if (workspaceId == null || workspaceId.isBlank()) {
            throw validation("workspaceId is required.");
        }
        return store.get(storageKey(region, workspaceId))
                .orElseThrow(() -> notFound(workspaceId, "Workspace"));
    }

    private GrafanaWorkspace requireWorkspaceByArn(String region, String arn) {
        String workspaceId = workspaceIdFromArn(arn);
        GrafanaWorkspace workspace = requireWorkspace(region, workspaceId);
        String expected = arnFor(workspace);
        if (!expected.equals(arn)) {
            throw notFound(arn, "Workspace");
        }
        return workspace;
    }

    private ServiceAccount requireServiceAccount(GrafanaWorkspace workspace, String serviceAccountId) {
        if (serviceAccountId == null || serviceAccountId.isBlank()) {
            throw validation("serviceAccountId is required.");
        }
        ServiceAccount account = workspace.getServiceAccounts().get(serviceAccountId);
        if (account == null) {
            throw notFound(serviceAccountId, "ServiceAccount");
        }
        return account;
    }

    private String arnFor(GrafanaWorkspace workspace) {
        return AwsArnUtils.Arn.of(
                SERVICE,
                workspace.getRegion(),
                workspace.getAccountId(),
                "/workspaces/" + workspace.getId()).toString();
    }

    private static String workspaceIdFromArn(String arn) {
        try {
            String resource = AwsArnUtils.parse(arn).resource();
            String prefix = "/workspaces/";
            if (resource != null && resource.startsWith(prefix)) {
                return resource.substring(prefix.length());
            }
            if (resource != null && resource.startsWith("workspaces/")) {
                return resource.substring("workspaces/".length());
            }
        } catch (IllegalArgumentException ignored) {
            // fall through
        }
        throw new AwsException("ValidationException", "Invalid Grafana workspace ARN: " + arn, 400);
    }

    private void persist(GrafanaWorkspace workspace) {
        store.put(storageKey(workspace.getRegion(), workspace.getId()), workspace);
    }

    private static String storageKey(String region, String workspaceId) {
        return region + "::" + workspaceId;
    }

    private ObjectNode paginateStrings(List<String> items, String maxResults, String nextToken, String field) {
        Page<String> page = paginate(items, maxResults, nextToken);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode array = response.putArray(field);
        for (String item : page.items()) {
            array.add(item);
        }
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return response;
    }

    private <T> Page<T> paginate(List<T> items, String maxResults, String nextToken) {
        int limit = DEFAULT_MAX_RESULTS;
        if (maxResults != null && !maxResults.isBlank()) {
            try {
                limit = Integer.parseInt(maxResults);
            } catch (NumberFormatException e) {
                throw validation("maxResults must be an integer.");
            }
            if (limit < 1 || limit > MAX_RESULTS) {
                throw validation("maxResults must be between 1 and " + MAX_RESULTS + ".");
            }
        }
        int offset = 0;
        if (nextToken != null && !nextToken.isBlank()) {
            offset = decodeToken(nextToken);
            if (offset < 0 || offset > items.size()) {
                throw validation("Invalid nextToken.");
            }
        }
        int end = Math.min(items.size(), offset + limit);
        String token = end < items.size() ? encodeToken(end) : null;
        return new Page<>(items.subList(offset, end), token);
    }

    private static String encodeToken(int offset) {
        return TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(Integer.toString(offset).getBytes(StandardCharsets.UTF_8));
    }

    private static int decodeToken(String token) {
        if (!token.startsWith(TOKEN_PREFIX)) {
            throw validation("Invalid nextToken.");
        }
        try {
            String decoded = new String(
                    Base64.getUrlDecoder().decode(token.substring(TOKEN_PREFIX.length())),
                    StandardCharsets.UTF_8);
            return Integer.parseInt(decoded);
        } catch (RuntimeException e) {
            throw validation("Invalid nextToken.");
        }
    }

    private static String newWorkspaceId() {
        String alphabet = "abcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder id = new StringBuilder("g-");
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < 10; i++) {
            id.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return id.toString();
    }

    private static String newShortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private static String newGrafanaTokenKey() {
        byte[] bytes = new byte[24];
        ThreadLocalRandom.current().nextBytes(bytes);
        return "glsa_" + HexFormat.of().formatHex(bytes);
    }

    private static int compareVersions(String left, String right) {
        String[] a = left.split("\\.");
        String[] b = right.split("\\.");
        int n = Math.max(a.length, b.length);
        for (int i = 0; i < n; i++) {
            int av = i < a.length ? parseVersionPart(a[i]) : 0;
            int bv = i < b.length ? parseVersionPart(b[i]) : 0;
            if (av != bv) {
                return Integer.compare(av, bv);
            }
        }
        return 0;
    }

    private static int parseVersionPart(String part) {
        try {
            return Integer.parseInt(part);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static Map<String, String> readTags(JsonNode request) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (request == null || !request.has("tags") || request.get("tags").isNull()) {
            return tags;
        }
        JsonNode node = request.get("tags");
        if (!node.isObject()) {
            throw validation("tags must be an object.");
        }
        node.fields().forEachRemaining(entry -> {
            if (!entry.getValue().isTextual()) {
                throw validation("tag values must be strings.");
            }
            tags.put(entry.getKey(), entry.getValue().textValue());
        });
        return tags;
    }

    private static void requireObject(JsonNode node, String field) {
        if (node == null || !node.isObject()) {
            throw validation(field + " must be a JSON object.");
        }
    }

    private static String requireText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isTextual() || value.textValue().isBlank()) {
            throw validation(field + " is required.");
        }
        return value.textValue();
    }

    private static String requireEnum(JsonNode node, String field, Set<String> allowed) {
        String value = requireText(node, field);
        if (!allowed.contains(value)) {
            throw validation(field + " is invalid: " + value);
        }
        return value;
    }

    private static int requireInt(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isNumber()) {
            throw validation(field + " is required.");
        }
        return value.intValue();
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw validation(field + " must be a string.");
        }
        String text = value.textValue();
        return text.isBlank() ? null : text;
    }

    private static List<String> requireStringList(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isArray()) {
            throw validation(field + " is required.");
        }
        List<String> items = new ArrayList<>();
        for (JsonNode item : value) {
            if (!item.isTextual()) {
                throw validation(field + " members must be strings.");
            }
            items.add(item.textValue());
        }
        return items;
    }

    private static List<String> optionalStringList(JsonNode node, String field) {
        if (!node.has(field) || node.get(field).isNull()) {
            return new ArrayList<>();
        }
        return requireStringList(node, field);
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    private static AwsException notFound(String resourceId, String resourceType) {
        return new AwsException(
                "ResourceNotFoundException",
                resourceType + " not found: " + resourceId,
                404,
                Map.of("resourceId", resourceId, "resourceType", resourceType));
    }

    private static AwsException conflict(String resourceId, String resourceType) {
        return new AwsException(
                "ConflictException",
                resourceType + " already exists: " + resourceId,
                409,
                Map.of("resourceId", resourceId, "resourceType", resourceType));
    }

    private record Page<T>(List<T> items, String nextToken) {
    }
}
