package io.floci.aws.services.grafana;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.ssoadmin.SsoAdminService;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

/**
 * Amazon Managed Grafana: workspaces, each backed by a real Grafana of its version running in Docker and served at
 * its endpoint, with their authentication, configuration, Identity Center permissions, Enterprise licenses, service
 * accounts and tokens, API keys and tags. A service account or token a workspace's API makes is made in its Grafana,
 * so the {@code glsa_} key it returns works against the endpoint. Without Docker, or with {@code mock: true}, a
 * workspace is ACTIVE without a Grafana and its keys are minted in the same format.
 */
@ApplicationScoped
public class GrafanaService implements Resettable {

    private static final Logger LOG = Logger.getLogger(GrafanaService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALPHANUMERIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final Pattern NAME = Pattern.compile("[a-zA-Z0-9\\-._~]{1,255}");
    private static final Pattern WORKSPACE_ID = Pattern.compile("g-[0-9a-f]{10}");
    private static final Set<String> ACCESS_TYPES = Set.of("CURRENT_ACCOUNT", "ORGANIZATION");
    private static final Set<String> PROVIDERS = Set.of("AWS_SSO", "SAML");
    private static final Set<String> PERMISSION_TYPES = Set.of("CUSTOMER_MANAGED", "SERVICE_MANAGED");
    private static final Set<String> DATA_SOURCES = Set.of("AMAZON_OPENSEARCH_SERVICE", "CLOUDWATCH", "PROMETHEUS",
            "XRAY", "TIMESTREAM", "SITEWISE", "ATHENA", "REDSHIFT", "TWINMAKER");
    private static final Set<String> ROLES = Set.of("ADMIN", "EDITOR", "VIEWER", "NONE");
    private static final long MAX_SECONDS_TO_LIVE = 30L * 24 * 3600;
    private static final int MAX_WORKSPACES = 5;

    private final AccountAwareStorageBackend<ObjectNode> storage;
    private final ObjectMapper mapper;
    private final EmulatorConfig config;
    private final GrafanaContainerManager containers;
    private final SsoAdminService ssoAdmin;
    private final ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "grafana-workspaces");
        thread.setDaemon(true);
        return thread;
    });

    @Inject
    public GrafanaService(StorageFactory storageFactory, ObjectMapper mapper, EmulatorConfig config,
                          GrafanaContainerManager containers, SsoAdminService ssoAdmin) {
        this.storage = storageFactory.create("grafana", "workspaces.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.mapper = mapper;
        this.config = config;
        this.containers = containers;
        this.ssoAdmin = ssoAdmin;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    @Override
    public void clear() {
        if (!mock()) {
            try {
                containers.stopManagedContainers();
            } catch (RuntimeException e) {
                LOG.warnv("Failed to stop Grafana containers during reset: {0}", e.getMessage());
            }
        }
        storage.clear();
    }

    private boolean mock() {
        return config.services().grafana().mock();
    }

    private String account() {
        return storage.accountId();
    }

    // ---------------------------------------------------------------- records

    private static String key(String region, String workspaceId) {
        return region + "/workspace/" + workspaceId;
    }

    private ObjectNode workspace(String account, String region, String workspaceId) {
        if (workspaceId == null || !WORKSPACE_ID.matcher(workspaceId).matches()) {
            throw validation("workspaceId must match g-[0-9a-f]{10}.", "workspaceId");
        }
        ObjectNode workspace = storage.getForAccount(account, key(region, workspaceId))
                .orElseThrow(() -> notFound(workspaceId));
        return settle(account, region, workspace);
    }

    private void save(String account, String region, ObjectNode workspace) {
        workspace.put("modified", now());
        storage.putForAccount(account, key(region, workspace.path("id").asText()), workspace);
    }

    private static ObjectNode meta(ObjectNode workspace) {
        return (ObjectNode) workspace.get("_");
    }

    /** Applies the status a workspace without a Grafana reaches a transition after a change. */
    private ObjectNode settle(String account, String region, ObjectNode workspace) {
        ObjectNode meta = meta(workspace);
        if (meta.hasNonNull("settleAt") && meta.get("settleAt").asLong() <= System.currentTimeMillis()) {
            String next = meta.path("settleTo").asText();
            meta.remove(List.of("settleAt", "settleTo"));
            if ("GONE".equals(next)) {
                storage.deleteForAccount(account, key(region, workspace.path("id").asText()));
                return null;
            }
            workspace.put("status", next);
            save(account, region, workspace);
        }
        return workspace;
    }

    private void transition(ObjectNode workspace, String status, String next) {
        workspace.put("status", status);
        meta(workspace).put("settleAt", System.currentTimeMillis() + config.services().grafana().transitionMillis())
                .put("settleTo", next);
    }

    // ---------------------------------------------------------------- workspaces

    public synchronized ObjectNode createWorkspace(JsonNode input, String region) {
        String account = account();
        String clientToken = input.path("clientToken").asText(null);
        if (clientToken != null) {
            for (ObjectNode existing : list(account, region)) {
                if (clientToken.equals(meta(existing).path("clientToken").asText(null))) {
                    return wrap(view(existing));
                }
            }
        }
        if (list(account, region).size() >= MAX_WORKSPACES) {
            throw new AwsException("ServiceQuotaExceededException", "The maximum number of workspaces in this "
                    + "Region, " + MAX_WORKSPACES + ", has been reached.", 402, Map.of("resourceId", "",
                    "resourceType", "WORKSPACE", "serviceCode", "grafana", "quotaCode", "L-81A4D3FD"));
        }
        String accessType = required(input, "accountAccessType");
        if (!ACCESS_TYPES.contains(accessType)) {
            throw validation("accountAccessType must be CURRENT_ACCOUNT or ORGANIZATION.", "accountAccessType");
        }
        String permissionType = required(input, "permissionType");
        if (!PERMISSION_TYPES.contains(permissionType)) {
            throw validation("permissionType must be SERVICE_MANAGED or CUSTOMER_MANAGED.", "permissionType");
        }
        List<String> providers = providers(input.path("authenticationProviders"), account);
        String version = input.path("grafanaVersion").asText(GrafanaVersions.latest());
        if (!GrafanaVersions.supported(version)) {
            throw validation("grafanaVersion " + version + " is not supported. Supported versions are "
                    + String.join(", ", GrafanaVersions.all()) + ".", "grafanaVersion");
        }
        String id = "g-" + HexFormat.of().formatHex(bytes(5));
        String now = now();
        ObjectNode workspace = mapper.createObjectNode();
        workspace.put("id", id).put("accountAccessType", accessType).put("permissionType", permissionType)
                .put("grafanaVersion", version).put("created", now).put("modified", now)
                .put("endpoint", GrafanaEndpoint.publicEndpoint(id, region, config.effectiveBaseUrl()))
                .put("freeTrialConsumed", freeTrialConsumed(account, region));
        ObjectNode meta = workspace.putObject("_");
        meta.put("arn", AwsArnUtils.Arn.of("grafana", region, account, "/workspaces/" + id).toString())
                .put("adminPassword", token(24)).put("ssoClientId", token(22)).put("serviceAccountSeq", 1)
                .put("tokenSeq", 0);
        if (clientToken != null) {
            meta.put("clientToken", clientToken);
        }
        meta.set("providers", mapper.valueToTree(providers));
        meta.putObject("saml");
        meta.putObject("serviceAccounts");
        meta.putObject("apiKeys");
        meta.putArray("permissions");
        meta.put("configuration", configuration(input.path("configuration").asText(null), version));
        meta.set("tags", tags(input.path("tags"), mapper.createObjectNode()));
        applyMutable(workspace, input, accessType, permissionType, true);
        save(account, region, workspace);
        provision(account, region, workspace, "CREATING", "CREATION_FAILED");
        return wrap(view(workspace));
    }

    private List<String> providers(JsonNode node, String account) {
        if (!node.isArray() || node.isEmpty()) {
            throw validation("authenticationProviders must name AWS_SSO, SAML or both.", "authenticationProviders");
        }
        List<String> providers = new ArrayList<>();
        for (JsonNode provider : node) {
            if (!PROVIDERS.contains(provider.asText())) {
                throw validation("authenticationProviders takes AWS_SSO and SAML, not " + provider.asText() + ".",
                        "authenticationProviders");
            }
            if (!providers.contains(provider.asText())) {
                providers.add(provider.asText());
            }
        }
        if (providers.contains("AWS_SSO") && ssoAdmin.listInstances(account).isEmpty()) {
            throw validation("AWS IAM Identity Center is not enabled in this account. Enable it to use AWS_SSO "
                    + "authentication.", "authenticationProviders");
        }
        return providers;
    }

    /** Applies the settings CreateWorkspace and UpdateWorkspace share. */
    private void applyMutable(ObjectNode workspace, JsonNode input, String accessType, String permissionType,
                              boolean create) {
        String nameField = "workspaceName";
        if (input.hasNonNull(nameField)) {
            String name = input.get(nameField).asText();
            if (!NAME.matcher(name).matches()) {
                throw validation("workspaceName must be 1 to 255 characters of letters, digits and -._~.",
                        nameField);
            }
            workspace.put("name", name);
        }
        if (input.hasNonNull("workspaceDescription")) {
            String description = input.get("workspaceDescription").asText();
            if (description.length() > 2048) {
                throw validation("workspaceDescription is at most 2048 characters.", "workspaceDescription");
            }
            workspace.put("description", description);
        }
        if (input.has("workspaceDataSources")) {
            ArrayNode sources = workspace.putArray("dataSources");
            for (JsonNode source : input.get("workspaceDataSources")) {
                if (!DATA_SOURCES.contains(source.asText())) {
                    throw validation("Unknown data source " + source.asText() + ".", "workspaceDataSources");
                }
                sources.add(source.asText());
            }
        } else if (create) {
            workspace.putArray("dataSources");
        }
        if (input.has("workspaceNotificationDestinations")) {
            ArrayNode destinations = workspace.putArray("notificationDestinations");
            for (JsonNode destination : input.get("workspaceNotificationDestinations")) {
                if (!"SNS".equals(destination.asText())) {
                    throw validation("The only notification destination is SNS.", "workspaceNotificationDestinations");
                }
                destinations.add("SNS");
            }
        }
        if (input.has("workspaceOrganizationalUnits")) {
            workspace.set("organizationalUnits", input.get("workspaceOrganizationalUnits").deepCopy());
        }
        if ("ORGANIZATION".equals(accessType) && workspace.path("organizationalUnits").isEmpty()) {
            throw validation("An ORGANIZATION workspace names the organizational units it reads in "
                    + "workspaceOrganizationalUnits.", "workspaceOrganizationalUnits");
        }
        for (String field : List.of("organizationRoleName", "stackSetName")) {
            if (input.hasNonNull(field)) {
                workspace.put(field, input.get(field).asText());
            }
        }
        if (input.hasNonNull("workspaceRoleArn")) {
            String role = input.get("workspaceRoleArn").asText();
            if (!AwsArnUtils.isArnFor(role, "iam") || !AwsArnUtils.parse(role).resource().startsWith("role/")) {
                throw validation("workspaceRoleArn must be an IAM role ARN.", "workspaceRoleArn");
            }
            workspace.put("workspaceRoleArn", role);
        }
        if ("CUSTOMER_MANAGED".equals(permissionType) && create && !workspace.hasNonNull("workspaceRoleArn")
                && !workspace.path("dataSources").isEmpty()) {
            throw validation("A CUSTOMER_MANAGED workspace with data sources needs a workspaceRoleArn.",
                    "workspaceRoleArn");
        }
        if (input.has("vpcConfiguration")) {
            JsonNode vpc = input.get("vpcConfiguration");
            if (vpc.path("subnetIds").size() < 2 || vpc.path("securityGroupIds").isEmpty()) {
                throw validation("vpcConfiguration takes 2 to 6 subnetIds and 1 to 5 securityGroupIds.",
                        "vpcConfiguration");
            }
            workspace.set("vpcConfiguration", vpc.deepCopy());
        }
        if (input.path("removeVpcConfiguration").asBoolean(false)) {
            workspace.remove("vpcConfiguration");
        }
        if (input.has("networkAccessControl")) {
            workspace.set("networkAccessControl", input.get("networkAccessControl").deepCopy());
        }
        if (input.path("removeNetworkAccessConfiguration").asBoolean(false)) {
            workspace.remove("networkAccessControl");
        }
    }

    public synchronized ObjectNode describeWorkspace(String workspaceId, String region) {
        return wrap(view(require(region, workspaceId)));
    }

    public synchronized ObjectNode listWorkspaces(String maxResults, String nextToken, String region) {
        List<ObjectNode> summaries = new ArrayList<>();
        for (ObjectNode workspace : list(account(), region)) {
            ObjectNode view = view(workspace);
            ObjectNode summary = mapper.createObjectNode();
            for (String field : List.of("authentication", "created", "description", "endpoint", "grafanaVersion",
                    "id", "licenseType", "modified", "name", "notificationDestinations", "status", "tags")) {
                if (view.has(field)) {
                    summary.set(field, view.get(field));
                }
            }
            summaries.add(summary);
        }
        return page(summaries, maxResults, nextToken, 100, "workspaces");
    }

    public synchronized ObjectNode updateWorkspace(String workspaceId, JsonNode input, String region) {
        String account = account();
        ObjectNode workspace = active(region, workspaceId);
        String accessType = input.path("accountAccessType").asText(workspace.path("accountAccessType").asText());
        String permissionType = input.path("permissionType").asText(workspace.path("permissionType").asText());
        if (!ACCESS_TYPES.contains(accessType)) {
            throw validation("accountAccessType must be CURRENT_ACCOUNT or ORGANIZATION.", "accountAccessType");
        }
        if (!PERMISSION_TYPES.contains(permissionType)) {
            throw validation("permissionType must be SERVICE_MANAGED or CUSTOMER_MANAGED.", "permissionType");
        }
        workspace.put("accountAccessType", accessType).put("permissionType", permissionType);
        applyMutable(workspace, input, accessType, permissionType, false);
        transition(workspace, "UPDATING", "ACTIVE");
        save(account, region, workspace);
        settle(account, region, workspace);
        return wrap(view(workspace));
    }

    public synchronized ObjectNode deleteWorkspace(String workspaceId, String region) {
        String account = account();
        ObjectNode workspace = require(region, workspaceId);
        String containerId = meta(workspace).path("containerId").asText(null);
        ObjectNode view = view(workspace);
        view.put("status", "DELETING");
        if (containerId != null || !mock()) {
            executor.execute(() -> {
                try {
                    containers.stop(workspaceId, containerId);
                    containers.removeStorage(workspaceId);
                } catch (RuntimeException e) {
                    LOG.warnv("Failed to remove the Grafana container of {0}: {1}", workspaceId, e.getMessage());
                }
            });
        }
        transition(workspace, "DELETING", "GONE");
        save(account, region, workspace);
        settle(account, region, workspace);
        return wrap(view);
    }

    // ---------------------------------------------------------------- provisioning

    /**
     * Brings a workspace's Grafana up, or back up with its current version and settings, and marks it ACTIVE (or
     * {@code failed}) when it answers. Without Docker the workspace is ACTIVE a transition later.
     */
    private void provision(String account, String region, ObjectNode workspace, String status, String failed) {
        String workspaceId = workspace.path("id").asText();
        if (mock() || !containers.isDockerReachable()) {
            if (!mock()) {
                LOG.warnv("No Docker daemon is reachable; Grafana workspace {0} is ACTIVE without a Grafana",
                        workspaceId);
            }
            transition(workspace, status, "ACTIVE");
            save(account, region, workspace);
            settle(account, region, workspace);
            return;
        }
        workspace.put("status", status);
        save(account, region, workspace);
        String version = workspace.path("grafanaVersion").asText();
        String password = meta(workspace).path("adminPassword").asText();
        Map<String, String> settings = settings(meta(workspace).path("configuration").asText());
        String rootUrl = GrafanaEndpoint.rootUrl(workspaceId, region, config.effectiveBaseUrl());
        executor.execute(() -> {
            GrafanaContainerManager.Endpoint endpoint = null;
            String outcome = "ACTIVE";
            try {
                endpoint = containers.start(workspaceId, account, region, version, rootUrl, password, settings);
            } catch (RuntimeException e) {
                LOG.errorv(e, "Failed to start the Grafana of workspace {0}", workspaceId);
                outcome = failed;
            }
            synchronized (GrafanaService.this) {
                ObjectNode current = storage.getForAccount(account, key(region, workspaceId)).orElse(null);
                if (current == null || "DELETING".equals(current.path("status").asText())) {
                    if (endpoint != null) {
                        containers.stop(workspaceId, endpoint.containerId());
                    }
                    return;
                }
                if (endpoint != null) {
                    meta(current).put("containerId", endpoint.containerId()).put("host", endpoint.host())
                            .put("port", endpoint.port());
                }
                current.put("status", outcome);
                save(account, region, current);
            }
        });
    }

    /** Grafana's settings for a workspace configuration: plugin management and unified alerting. */
    private Map<String, String> settings(String configuration) {
        Map<String, String> settings = new LinkedHashMap<>();
        try {
            JsonNode parsed = mapper.readTree(configuration);
            JsonNode plugins = parsed.path("plugins").path("pluginAdminEnabled");
            if (!plugins.isMissingNode()) {
                settings.put("GF_PLUGINS_PLUGIN_ADMIN_ENABLED", Boolean.toString(plugins.asBoolean()));
            }
            JsonNode alerting = parsed.path("unifiedAlerting").path("enabled");
            if (!alerting.isMissingNode()) {
                settings.put("GF_UNIFIED_ALERTING_ENABLED", Boolean.toString(alerting.asBoolean()));
                settings.put("GF_ALERTING_ENABLED", "false");
            }
        } catch (JsonProcessingException e) {
            LOG.debugv("Workspace configuration is not JSON: {0}", e.getMessage());
        }
        return settings;
    }

    /** The running Grafana of an ACTIVE workspace, or null when it has none. */
    GrafanaContainerManager.Endpoint endpoint(ObjectNode workspace) {
        ObjectNode meta = meta(workspace);
        if (!meta.hasNonNull("containerId")) {
            return null;
        }
        return new GrafanaContainerManager.Endpoint(meta.get("containerId").asText(), meta.path("host").asText(),
                meta.path("port").asInt(), meta.path("adminPassword").asText());
    }

    /** Where the data plane forwards a workspace's requests, or empty when it has no Grafana running. */
    synchronized Optional<GrafanaContainerManager.Endpoint> dataPlane(String account, String region,
                                                                     String workspaceId) {
        return storage.getForAccount(account, key(region, workspaceId)).map(this::endpoint);
    }

    /** The account that owns a workspace, whichever account the request is signed by. */
    synchronized Optional<String> owner(String region, String workspaceId) {
        return storage.findAnyAccountEntry(key(region, workspaceId)).map(AccountAwareStorageBackend.OwnedEntry::account);
    }

    /** Starts the Grafana of every workspace persisted by a previous run. */
    public synchronized void restorePersistedRuntime() {
        if (mock()) {
            return;
        }
        for (AccountAwareStorageBackend.AccountEntry<ObjectNode> entry : storage.scanAllAccountEntries(
                key -> key.contains("/workspace/"))) {
            ObjectNode workspace = entry.value();
            if (meta(workspace).hasNonNull("containerId")) {
                String region = entry.key().substring(0, entry.key().indexOf('/'));
                meta(workspace).remove("containerId");
                provision(entry.accountId(), region, workspace, workspace.path("status").asText("ACTIVE"),
                        "FAILED");
            }
        }
    }

    // ---------------------------------------------------------------- authentication and configuration

    public synchronized ObjectNode describeWorkspaceAuthentication(String workspaceId, String region) {
        return authentication(require(region, workspaceId));
    }

    private ObjectNode authentication(ObjectNode workspace) {
        ObjectNode meta = meta(workspace);
        ObjectNode response = mapper.createObjectNode();
        ObjectNode authentication = response.putObject("authentication");
        authentication.set("providers", meta.path("providers").deepCopy());
        if (providerList(workspace).contains("SAML")) {
            ObjectNode saml = authentication.putObject("saml");
            saml.put("status", samlStatus(workspace));
            if (meta.path("saml").size() > 0) {
                saml.set("configuration", meta.get("saml").deepCopy());
            }
        }
        if (providerList(workspace).contains("AWS_SSO")) {
            authentication.putObject("awsSso").put("ssoClientId", meta.path("ssoClientId").asText());
        }
        return response;
    }

    private static String samlStatus(ObjectNode workspace) {
        JsonNode metadata = meta(workspace).path("saml").path("idpMetadata");
        return metadata.hasNonNull("url") || metadata.hasNonNull("xml") ? "CONFIGURED" : "NOT_CONFIGURED";
    }

    private List<String> providerList(ObjectNode workspace) {
        List<String> providers = new ArrayList<>();
        meta(workspace).path("providers").forEach(provider -> providers.add(provider.asText()));
        return providers;
    }

    public synchronized ObjectNode updateWorkspaceAuthentication(String workspaceId, JsonNode input, String region) {
        String account = account();
        ObjectNode workspace = active(region, workspaceId);
        List<String> providers = providers(input.path("authenticationProviders"), account);
        meta(workspace).set("providers", mapper.valueToTree(providers));
        if (input.has("samlConfiguration")) {
            JsonNode saml = input.get("samlConfiguration");
            JsonNode metadata = saml.path("idpMetadata");
            if (!metadata.hasNonNull("url") && !metadata.hasNonNull("xml")) {
                throw validation("samlConfiguration.idpMetadata takes a url or the metadata xml.",
                        "samlConfiguration.idpMetadata");
            }
            int duration = saml.path("loginValidityDuration").asInt(120);
            if (duration < 1) {
                throw validation("loginValidityDuration is in minutes, 1 or more.", "samlConfiguration");
            }
            ObjectNode stored = saml.deepCopy();
            stored.put("loginValidityDuration", duration);
            meta(workspace).set("saml", stored);
        }
        if (!providers.contains("SAML")) {
            meta(workspace).putObject("saml");
        }
        save(account, region, workspace);
        return authentication(workspace);
    }

    public synchronized ObjectNode describeWorkspaceConfiguration(String workspaceId, String region) {
        ObjectNode workspace = require(region, workspaceId);
        ObjectNode response = mapper.createObjectNode();
        response.put("configuration", meta(workspace).path("configuration").asText())
                .put("grafanaVersion", workspace.path("grafanaVersion").asText());
        return response;
    }

    public synchronized ObjectNode updateWorkspaceConfiguration(String workspaceId, JsonNode input, String region) {
        String account = account();
        ObjectNode workspace = active(region, workspaceId);
        String version = workspace.path("grafanaVersion").asText();
        String upgrade = input.path("grafanaVersion").asText(null);
        if (upgrade != null && !upgrade.equals(version)) {
            if (!GrafanaVersions.supported(upgrade)) {
                throw validation("grafanaVersion " + upgrade + " is not supported.", "grafanaVersion");
            }
            if (!GrafanaVersions.after(version).contains(upgrade)) {
                throw validation("A workspace can only be upgraded: " + version + " cannot become " + upgrade + ".",
                        "grafanaVersion");
            }
            workspace.put("grafanaVersion", upgrade);
        }
        String configuration = required(input, "configuration");
        meta(workspace).put("configuration", configuration(configuration, workspace.path("grafanaVersion").asText()));
        save(account, region, workspace);
        provision(account, region, workspace, upgrade != null && !upgrade.equals(version) ? "VERSION_UPDATING"
                : "UPDATING", upgrade != null && !upgrade.equals(version) ? "VERSION_UPDATE_FAILED" : "UPDATE_FAILED");
        return mapper.createObjectNode();
    }

    /** A workspace configuration as Grafana takes it, with the version's defaults for what it leaves out. */
    private String configuration(String given, String version) {
        ObjectNode defaults = mapper.createObjectNode();
        defaults.putObject("plugins").put("pluginAdminEnabled", false);
        defaults.putObject("unifiedAlerting").put("enabled", GrafanaVersions.unifiedAlertingByDefault(version));
        if (given == null || given.isBlank()) {
            return defaults.toString();
        }
        JsonNode parsed;
        try {
            parsed = mapper.readTree(given);
        } catch (JsonProcessingException e) {
            throw validation("configuration is not valid JSON: " + e.getOriginalMessage(), "configuration");
        }
        if (parsed == null || !parsed.isObject()) {
            throw validation("configuration must be a JSON object.", "configuration");
        }
        parsed.fieldNames().forEachRemaining(field -> {
            if (!Set.of("plugins", "unifiedAlerting").contains(field)) {
                throw validation("configuration takes plugins and unifiedAlerting, not " + field + ".",
                        "configuration");
            }
        });
        if (parsed.has("plugins")) {
            defaults.set("plugins", parsed.get("plugins").deepCopy());
        }
        if (parsed.has("unifiedAlerting")) {
            defaults.set("unifiedAlerting", parsed.get("unifiedAlerting").deepCopy());
        }
        return defaults.toString();
    }

    // ---------------------------------------------------------------- permissions

    public synchronized ObjectNode listPermissions(String workspaceId, String userType, String userId,
                                                   String groupId, String maxResults, String nextToken,
                                                   String region) {
        ObjectNode workspace = require(region, workspaceId);
        requireSso(workspace);
        List<ObjectNode> permissions = new ArrayList<>();
        for (JsonNode permission : meta(workspace).path("permissions")) {
            JsonNode user = permission.path("user");
            if (userType != null && !userType.equals(user.path("type").asText())) {
                continue;
            }
            if (userId != null && !("SSO_USER".equals(user.path("type").asText())
                    && userId.equals(user.path("id").asText()))) {
                continue;
            }
            if (groupId != null && !("SSO_GROUP".equals(user.path("type").asText())
                    && groupId.equals(user.path("id").asText()))) {
                continue;
            }
            permissions.add(((ObjectNode) permission).deepCopy());
        }
        return page(permissions, maxResults, nextToken, 100, "permissions");
    }

    private void requireSso(ObjectNode workspace) {
        if (!providerList(workspace).contains("AWS_SSO")) {
            throw validation("Workspace " + workspace.path("id").asText() + " does not use AWS IAM Identity Center "
                    + "authentication, so it has no Identity Center permissions.", "workspaceId");
        }
    }

    public synchronized ObjectNode updatePermissions(String workspaceId, JsonNode input, String region) {
        String account = account();
        ObjectNode workspace = active(region, workspaceId);
        requireSso(workspace);
        Map<String, ObjectNode> permissions = new LinkedHashMap<>();
        for (JsonNode permission : meta(workspace).path("permissions")) {
            permissions.put(permission.path("user").path("type").asText() + "/" + permission.path("user").path("id")
                    .asText(), (ObjectNode) permission);
        }
        ObjectNode response = mapper.createObjectNode();
        ArrayNode errors = response.putArray("errors");
        for (JsonNode instruction : input.path("updateInstructionBatch")) {
            String action = instruction.path("action").asText();
            String role = instruction.path("role").asText();
            for (JsonNode user : instruction.path("users")) {
                String type = user.path("type").asText();
                String id = user.path("id").asText();
                String failure = null;
                if (!Set.of("ADD", "REVOKE").contains(action)) {
                    failure = "action must be ADD or REVOKE.";
                } else if (!Set.of("ADMIN", "EDITOR", "VIEWER").contains(role)) {
                    failure = "role must be ADMIN, EDITOR or VIEWER.";
                } else if (!Set.of("SSO_USER", "SSO_GROUP").contains(type)) {
                    failure = "user type must be SSO_USER or SSO_GROUP.";
                } else if (id.isEmpty()) {
                    failure = "user id is required.";
                }
                if (failure != null) {
                    ObjectNode error = errors.addObject().put("code", 400).put("message", failure);
                    error.set("causedBy", instruction.deepCopy());
                    continue;
                }
                String key = type + "/" + id;
                if ("ADD".equals(action)) {
                    ObjectNode permission = mapper.createObjectNode();
                    permission.put("role", role).putObject("user").put("id", id).put("type", type);
                    permissions.put(key, permission);
                } else if (permissions.containsKey(key) && role.equals(permissions.get(key).path("role").asText())) {
                    permissions.remove(key);
                }
            }
        }
        ArrayNode stored = meta(workspace).putArray("permissions");
        permissions.values().forEach(stored::add);
        save(account, region, workspace);
        return response;
    }

    // ---------------------------------------------------------------- licenses

    public synchronized ObjectNode associateLicense(String workspaceId, String licenseType, String grafanaToken,
                                                    String region) {
        String account = account();
        ObjectNode workspace = active(region, workspaceId);
        Instant now = Instant.now();
        switch (licenseType) {
            case "ENTERPRISE" -> {
                if (grafanaToken == null || grafanaToken.isEmpty()) {
                    throw validation("An ENTERPRISE license needs the Grafana Labs token in the Grafana-Token "
                            + "header.", "grafanaToken");
                }
                workspace.put("licenseType", "ENTERPRISE")
                        .put("licenseExpiration", now.plus(365, ChronoUnit.DAYS).toString());
                meta(workspace).put("grafanaToken", grafanaToken);
            }
            case "ENTERPRISE_FREE_TRIAL" -> {
                if (freeTrialConsumed(account, region)) {
                    throw validation("This account has already used its Grafana Enterprise free trial.",
                            "licenseType");
                }
                String expiration = now.plus(30, ChronoUnit.DAYS).toString();
                workspace.put("licenseType", "ENTERPRISE_FREE_TRIAL").put("licenseExpiration", expiration)
                        .put("freeTrialExpiration", expiration).put("freeTrialConsumed", true);
                ObjectNode trial = mapper.createObjectNode();
                trial.put("consumed", true);
                storage.putForAccount(account, region + "/free-trial", trial);
            }
            default -> throw validation("licenseType must be ENTERPRISE or ENTERPRISE_FREE_TRIAL.", "licenseType");
        }
        save(account, region, workspace);
        return wrap(view(workspace));
    }

    public synchronized ObjectNode disassociateLicense(String workspaceId, String licenseType, String region) {
        String account = account();
        ObjectNode workspace = active(region, workspaceId);
        if (!Set.of("ENTERPRISE", "ENTERPRISE_FREE_TRIAL").contains(licenseType)) {
            throw validation("licenseType must be ENTERPRISE or ENTERPRISE_FREE_TRIAL.", "licenseType");
        }
        if (licenseType.equals(workspace.path("licenseType").asText())) {
            workspace.remove(List.of("licenseType", "licenseExpiration", "freeTrialExpiration"));
            meta(workspace).remove("grafanaToken");
        }
        save(account, region, workspace);
        return wrap(view(workspace));
    }

    private boolean freeTrialConsumed(String account, String region) {
        return storage.getForAccount(account, region + "/free-trial").isPresent();
    }

    // ---------------------------------------------------------------- service accounts and tokens

    public synchronized ObjectNode createServiceAccount(String workspaceId, JsonNode input, String region) {
        String account = account();
        ObjectNode workspace = active(region, workspaceId);
        String name = required(input, "name");
        String role = required(input, "grafanaRole");
        if (!ROLES.contains(role)) {
            throw validation("grafanaRole must be ADMIN, EDITOR, VIEWER or NONE.", "grafanaRole");
        }
        ObjectNode accounts = meta(workspace).withObject("/serviceAccounts");
        accounts.forEach(existing -> {
            if (name.equals(existing.path("name").asText())) {
                throw conflict("A service account named " + name + " already exists.", name, "SERVICE_ACCOUNT");
            }
        });
        String id = makeServiceAccount(workspace, name, role);
        ObjectNode created = accounts.putObject(id);
        created.put("id", id).put("name", name).put("grafanaRole", role).put("isDisabled", "false");
        created.putObject("tokens");
        save(account, region, workspace);
        ObjectNode response = mapper.createObjectNode();
        response.put("id", id).put("name", name).put("grafanaRole", role).put("workspaceId", workspaceId);
        return response;
    }

    private String makeServiceAccount(ObjectNode workspace, String name, String role) {
        GrafanaContainerManager.Endpoint endpoint = endpoint(workspace);
        if (endpoint == null) {
            int next = meta(workspace).path("serviceAccountSeq").asInt() + 1;
            meta(workspace).put("serviceAccountSeq", next);
            return Integer.toString(next);
        }
        try {
            return containers.createServiceAccount(endpoint, name, grafanaRole(role));
        } catch (IllegalStateException e) {
            throw internal(e);
        }
    }

    private static String grafanaRole(String role) {
        return role.charAt(0) + role.substring(1).toLowerCase(Locale.ROOT);
    }

    private ObjectNode serviceAccount(ObjectNode workspace, String serviceAccountId) {
        JsonNode found = meta(workspace).path("serviceAccounts").get(serviceAccountId);
        if (found == null) {
            throw new AwsException("ResourceNotFoundException", "Service account " + serviceAccountId
                    + " not found.", 404, Map.of("resourceId", serviceAccountId, "resourceType", "SERVICE_ACCOUNT"));
        }
        return (ObjectNode) found;
    }

    public synchronized ObjectNode deleteServiceAccount(String workspaceId, String serviceAccountId, String region) {
        String account = account();
        ObjectNode workspace = active(region, workspaceId);
        serviceAccount(workspace, serviceAccountId);
        GrafanaContainerManager.Endpoint endpoint = endpoint(workspace);
        if (endpoint != null) {
            try {
                containers.deleteServiceAccount(endpoint, serviceAccountId);
            } catch (IllegalStateException e) {
                throw internal(e);
            }
        }
        meta(workspace).withObject("/serviceAccounts").remove(serviceAccountId);
        save(account, region, workspace);
        ObjectNode response = mapper.createObjectNode();
        response.put("serviceAccountId", serviceAccountId).put("workspaceId", workspaceId);
        return response;
    }

    public synchronized ObjectNode listServiceAccounts(String workspaceId, String maxResults, String nextToken,
                                                       String region) {
        ObjectNode workspace = require(region, workspaceId);
        List<ObjectNode> accounts = new ArrayList<>();
        meta(workspace).path("serviceAccounts").forEach(entry -> {
            if (!entry.path("_apiKey").asBoolean(false)) {
                ObjectNode item = mapper.createObjectNode();
                item.put("id", entry.path("id").asText()).put("name", entry.path("name").asText())
                        .put("grafanaRole", entry.path("grafanaRole").asText())
                        .put("isDisabled", entry.path("isDisabled").asText());
                accounts.add(item);
            }
        });
        ObjectNode response = page(accounts, maxResults, nextToken, 100, "serviceAccounts");
        response.put("workspaceId", workspaceId);
        return response;
    }

    public synchronized ObjectNode createToken(String workspaceId, String serviceAccountId, JsonNode input,
                                               String region) {
        String account = account();
        ObjectNode workspace = active(region, workspaceId);
        ObjectNode serviceAccount = serviceAccount(workspace, serviceAccountId);
        String name = required(input, "name");
        long secondsToLive = secondsToLive(input);
        ObjectNode tokens = serviceAccount.withObject("/tokens");
        tokens.forEach(existing -> {
            if (name.equals(existing.path("name").asText())) {
                throw conflict("A token named " + name + " already exists for service account " + serviceAccountId
                        + ".", name, "SERVICE_ACCOUNT_TOKEN");
            }
        });
        GrafanaContainerManager.Token token = makeToken(workspace, serviceAccountId, name, secondsToLive);
        Instant now = Instant.now();
        tokens.putObject(token.id()).put("id", token.id()).put("name", name).put("createdAt", now.toString())
                .put("expiresAt", now.plusSeconds(secondsToLive).toString());
        save(account, region, workspace);
        ObjectNode response = mapper.createObjectNode();
        response.put("serviceAccountId", serviceAccountId).put("workspaceId", workspaceId);
        response.putObject("serviceAccountToken").put("id", token.id()).put("name", name).put("key", token.key());
        return response;
    }

    private long secondsToLive(JsonNode input) {
        if (!input.hasNonNull("secondsToLive")) {
            throw validation("secondsToLive is required.", "secondsToLive");
        }
        long seconds = input.get("secondsToLive").asLong();
        if (seconds < 1 || seconds > MAX_SECONDS_TO_LIVE) {
            throw validation("secondsToLive is 1 to " + MAX_SECONDS_TO_LIVE + " (30 days).", "secondsToLive");
        }
        return seconds;
    }

    private GrafanaContainerManager.Token makeToken(ObjectNode workspace, String serviceAccountId, String name,
                                                    long secondsToLive) {
        GrafanaContainerManager.Endpoint endpoint = endpoint(workspace);
        if (endpoint == null) {
            int next = meta(workspace).path("tokenSeq").asInt() + 1;
            meta(workspace).put("tokenSeq", next);
            return new GrafanaContainerManager.Token(Integer.toString(next), serviceAccountKey());
        }
        try {
            return containers.createToken(endpoint, serviceAccountId, name, secondsToLive);
        } catch (IllegalStateException e) {
            throw internal(e);
        }
    }

    /** A key in Grafana's service account token format: {@code glsa_<32 characters>_<crc32 of them>}. */
    static String serviceAccountKey() {
        String secret = token(32);
        CRC32 crc = new CRC32();
        crc.update(secret.getBytes(StandardCharsets.UTF_8));
        return "glsa_" + secret + "_" + String.format("%08x", crc.getValue());
    }

    public synchronized ObjectNode deleteToken(String workspaceId, String serviceAccountId, String tokenId,
                                               String region) {
        String account = account();
        ObjectNode workspace = active(region, workspaceId);
        ObjectNode serviceAccount = serviceAccount(workspace, serviceAccountId);
        if (!serviceAccount.path("tokens").has(tokenId)) {
            throw new AwsException("ResourceNotFoundException", "Token " + tokenId + " not found.", 404,
                    Map.of("resourceId", tokenId, "resourceType", "SERVICE_ACCOUNT_TOKEN"));
        }
        GrafanaContainerManager.Endpoint endpoint = endpoint(workspace);
        if (endpoint != null) {
            try {
                containers.deleteToken(endpoint, serviceAccountId, tokenId);
            } catch (IllegalStateException e) {
                throw internal(e);
            }
        }
        serviceAccount.withObject("/tokens").remove(tokenId);
        save(account, region, workspace);
        ObjectNode response = mapper.createObjectNode();
        response.put("serviceAccountId", serviceAccountId).put("tokenId", tokenId).put("workspaceId", workspaceId);
        return response;
    }

    public synchronized ObjectNode listTokens(String workspaceId, String serviceAccountId, String maxResults,
                                              String nextToken, String region) {
        ObjectNode workspace = require(region, workspaceId);
        ObjectNode serviceAccount = serviceAccount(workspace, serviceAccountId);
        List<ObjectNode> tokens = new ArrayList<>();
        serviceAccount.path("tokens").forEach(token -> tokens.add(((ObjectNode) token).deepCopy()));
        ObjectNode response = page(tokens, maxResults, nextToken, 100, "serviceAccountTokens");
        response.put("serviceAccountId", serviceAccountId).put("workspaceId", workspaceId);
        return response;
    }

    // ---------------------------------------------------------------- API keys

    /** An API key: a service account of the key's role with one token, as Grafana keeps API keys since 10. */
    public synchronized ObjectNode createApiKey(String workspaceId, JsonNode input, String region) {
        String account = account();
        ObjectNode workspace = active(region, workspaceId);
        String keyName = required(input, "keyName");
        String role = required(input, "keyRole");
        if (!Set.of("ADMIN", "EDITOR", "VIEWER").contains(role)) {
            throw validation("keyRole must be ADMIN, EDITOR or VIEWER.", "keyRole");
        }
        long secondsToLive = secondsToLive(input);
        ObjectNode keys = meta(workspace).withObject("/apiKeys");
        if (keys.has(keyName)) {
            throw conflict("An API key named " + keyName + " already exists.", keyName, "API_KEY");
        }
        String serviceAccountId = makeServiceAccount(workspace, "api-key-" + keyName, grafanaRole(role)
                .toUpperCase(Locale.ROOT));
        GrafanaContainerManager.Token token = makeToken(workspace, serviceAccountId, keyName, secondsToLive);
        keys.putObject(keyName).put("serviceAccountId", serviceAccountId).put("tokenId", token.id())
                .put("role", role).put("expiresAt", Instant.now().plusSeconds(secondsToLive).toString());
        save(account, region, workspace);
        ObjectNode response = mapper.createObjectNode();
        response.put("key", token.key()).put("keyName", keyName).put("workspaceId", workspaceId);
        return response;
    }

    public synchronized ObjectNode deleteApiKey(String workspaceId, String keyName, String region) {
        String account = account();
        ObjectNode workspace = active(region, workspaceId);
        JsonNode key = meta(workspace).path("apiKeys").get(keyName);
        if (key == null) {
            throw new AwsException("ResourceNotFoundException", "API key " + keyName + " not found.", 404,
                    Map.of("resourceId", keyName, "resourceType", "API_KEY"));
        }
        GrafanaContainerManager.Endpoint endpoint = endpoint(workspace);
        if (endpoint != null) {
            try {
                containers.deleteServiceAccount(endpoint, key.path("serviceAccountId").asText());
            } catch (IllegalStateException e) {
                throw internal(e);
            }
        }
        meta(workspace).withObject("/apiKeys").remove(keyName);
        save(account, region, workspace);
        ObjectNode response = mapper.createObjectNode();
        response.put("keyName", keyName).put("workspaceId", workspaceId);
        return response;
    }

    // ---------------------------------------------------------------- versions and tags

    public synchronized ObjectNode listVersions(String workspaceId, String maxResults, String nextToken,
                                                String region) {
        List<String> versions = workspaceId == null ? GrafanaVersions.all()
                : GrafanaVersions.after(require(region, workspaceId).path("grafanaVersion").asText());
        List<ObjectNode> items = new ArrayList<>();
        versions.forEach(version -> items.add(mapper.createObjectNode().put("v", version)));
        ObjectNode page = page(items, maxResults, nextToken, 100, "grafanaVersions");
        ArrayNode flat = mapper.createArrayNode();
        page.path("grafanaVersions").forEach(item -> flat.add(item.path("v").asText()));
        page.set("grafanaVersions", flat);
        return page;
    }

    public synchronized ObjectNode listTags(String resourceArn, String region) {
        ObjectNode workspace = byArn(resourceArn, region);
        ObjectNode response = mapper.createObjectNode();
        response.set("tags", meta(workspace).path("tags").deepCopy());
        return response;
    }

    public synchronized ObjectNode tagResource(String resourceArn, JsonNode input, String region) {
        ObjectNode workspace = byArn(resourceArn, region);
        meta(workspace).set("tags", tags(input.path("tags"), (ObjectNode) meta(workspace).path("tags")));
        save(account(), region, workspace);
        return mapper.createObjectNode();
    }

    public synchronized ObjectNode untagResource(String resourceArn, List<String> tagKeys, String region) {
        ObjectNode workspace = byArn(resourceArn, region);
        ObjectNode tags = (ObjectNode) meta(workspace).path("tags");
        tagKeys.forEach(tags::remove);
        save(account(), region, workspace);
        return mapper.createObjectNode();
    }

    private ObjectNode byArn(String resourceArn, String region) {
        if (!AwsArnUtils.isArnFor(resourceArn, "grafana")) {
            throw validation("resourceArn must be an Amazon Managed Grafana workspace ARN.", "resourceArn");
        }
        AwsArnUtils.Arn arn = AwsArnUtils.parse(resourceArn);
        String resource = arn.resource();
        if (!resource.startsWith("/workspaces/")) {
            throw validation("resourceArn must be an Amazon Managed Grafana workspace ARN.", "resourceArn");
        }
        if (!arn.accountId().equals(account()) || !arn.region().equals(region)) {
            throw notFound(resource.substring("/workspaces/".length()));
        }
        return require(region, resource.substring("/workspaces/".length()));
    }

    private ObjectNode tags(JsonNode given, ObjectNode existing) {
        ObjectNode tags = existing.deepCopy();
        given.fields().forEachRemaining(entry -> {
            String tagKey = entry.getKey();
            if (tagKey.isEmpty() || tagKey.length() > 128 || tagKey.toLowerCase(Locale.ROOT).startsWith("aws:")) {
                throw validation("Tag keys are 1 to 128 characters and do not begin with aws:.", "tags");
            }
            if (entry.getValue().asText().length() > 256) {
                throw validation("Tag values are at most 256 characters.", "tags");
            }
            tags.put(tagKey, entry.getValue().asText());
        });
        if (tags.size() > 50) {
            throw validation("A workspace has at most 50 tags.", "tags");
        }
        return tags;
    }

    // ---------------------------------------------------------------- shared

    private List<ObjectNode> list(String account, String region) {
        List<ObjectNode> workspaces = new ArrayList<>();
        for (ObjectNode workspace : storage.scanForAccount(account, key -> key.startsWith(region + "/workspace/"))) {
            ObjectNode settled = settle(account, region, workspace);
            if (settled != null) {
                workspaces.add(settled);
            }
        }
        workspaces.sort((a, b) -> a.path("created").asText().compareTo(b.path("created").asText()));
        return workspaces;
    }

    private ObjectNode require(String region, String workspaceId) {
        ObjectNode workspace = workspace(account(), region, workspaceId);
        if (workspace == null) {
            throw notFound(workspaceId);
        }
        return workspace;
    }

    private ObjectNode active(String region, String workspaceId) {
        ObjectNode workspace = require(region, workspaceId);
        if (!"ACTIVE".equals(workspace.path("status").asText())) {
            throw conflict("Workspace " + workspaceId + " is " + workspace.path("status").asText() + ", not ACTIVE.",
                    workspaceId, "WORKSPACE");
        }
        return workspace;
    }

    private ObjectNode view(ObjectNode workspace) {
        ObjectNode view = workspace.deepCopy();
        ObjectNode meta = (ObjectNode) view.remove("_");
        ObjectNode authentication = view.putObject("authentication");
        authentication.set("providers", meta.path("providers").deepCopy());
        if (providerList(workspace).contains("SAML")) {
            authentication.put("samlConfigurationStatus", samlStatus(workspace));
        }
        view.set("tags", meta.path("tags").deepCopy());
        return view;
    }

    private ObjectNode wrap(ObjectNode workspace) {
        ObjectNode response = mapper.createObjectNode();
        response.set("workspace", workspace);
        return response;
    }

    private ObjectNode page(List<ObjectNode> items, String maxResults, String nextToken, int max, String field) {
        int limit = max;
        if (maxResults != null && !maxResults.isEmpty()) {
            try {
                limit = Integer.parseInt(maxResults);
            } catch (NumberFormatException e) {
                limit = 0;
            }
            if (limit < 1 || limit > max) {
                throw validation("maxResults is 1 to " + max + ".", "maxResults");
            }
        }
        int start = 0;
        if (nextToken != null && !nextToken.isEmpty()) {
            try {
                start = Integer.parseInt(new String(Base64.getUrlDecoder().decode(nextToken),
                        StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                throw validation("nextToken is not valid.", "nextToken");
            }
        }
        ObjectNode response = mapper.createObjectNode();
        ArrayNode list = response.putArray(field);
        int end = Math.min(items.size(), start + limit);
        for (int i = start; i < end; i++) {
            list.add(items.get(i));
        }
        if (end < items.size()) {
            response.put("nextToken", Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(Integer.toString(end).getBytes(StandardCharsets.UTF_8)));
        }
        return response;
    }

    private static String required(JsonNode input, String field) {
        JsonNode value = input.get(field);
        if (value == null || value.isNull() || value.asText().isEmpty()) {
            throw validation(field + " is required.", field);
        }
        return value.asText();
    }

    private static String now() {
        return Instant.now().toString();
    }

    private static byte[] bytes(int count) {
        byte[] bytes = new byte[count];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    private static String token(int length) {
        StringBuilder out = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            out.append(ALPHANUMERIC.charAt(RANDOM.nextInt(ALPHANUMERIC.length())));
        }
        return out.toString();
    }

    static AwsException validation(String message, String field) {
        Map<String, Object> extended = new LinkedHashMap<>();
        extended.put("reason", "FIELD_VALIDATION_FAILED");
        extended.put("fieldList", List.of(Map.of("name", field, "message", message)));
        return new AwsException("ValidationException", message, 400, extended);
    }

    static AwsException notFound(String workspaceId) {
        return new AwsException("ResourceNotFoundException", "Workspace " + workspaceId + " not found.", 404,
                Map.of("resourceId", String.valueOf(workspaceId), "resourceType", "WORKSPACE"));
    }

    private static AwsException conflict(String message, String resourceId, String resourceType) {
        return new AwsException("ConflictException", message, 409, Map.of("resourceId", resourceId,
                "resourceType", resourceType));
    }

    private static AwsException internal(IllegalStateException e) {
        return new AwsException("InternalServerException", "The workspace's Grafana did not answer: "
                + e.getMessage(), 500);
    }

    /** Workspaces of every account, for status reporting. */
    Set<String> workspaceIds() {
        Set<String> ids = new LinkedHashSet<>();
        storage.scanAllAccounts().forEach(workspace -> ids.add(workspace.path("id").asText()));
        return ids;
    }
}
