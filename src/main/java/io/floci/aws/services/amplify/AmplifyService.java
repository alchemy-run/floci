package io.floci.aws.services.amplify;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.AwsRegions;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestConstraintCheck;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.route53.Route53Service;
import io.floci.aws.services.route53.model.HostedZone;
import io.floci.aws.services.route53.model.ResourceRecord;
import io.floci.aws.services.route53.model.ResourceRecordSet;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * AWS Amplify Hosting's resources: apps, their branches, backend environments, webhooks and
 * custom domain associations, with tags. Jobs and deployments are {@link AmplifyHosting}'s.
 * Requests are checked against the constraints vendored from botocore by
 * {@code tools/aws/regen_amplify_shapes.py}.
 */
@ApplicationScoped
public class AmplifyService implements Resettable {
    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final Set<String> STAGES = Set.of("PRODUCTION", "BETA", "DEVELOPMENT", "EXPERIMENTAL",
            "PULL_REQUEST");
    private static final Pattern DOMAIN = Pattern.compile("^(((?!-)[A-Za-z0-9-]{0,62}[A-Za-z0-9])\\.)+"
            + "((?!-)[A-Za-z0-9-]{1,62}[A-Za-z0-9])(\\.)?$");

    private static final RequestConstraintCheck SHAPES = RequestConstraintCheck.load(
            "aws/amplify-request-shapes.json", "Amplify request shapes", new RequestConstraintCheck.Messages() {
                @Override
                public AwsException type(String path, String type) {
                    return badRequest(path + " must be a " + type + ".");
                }

                @Override
                public AwsException bounds(String path, String type, Long min, Long max, long size) {
                    return badRequest(path + " is outside the allowed bounds" + (min == null ? "" : " (min " + min
                            + ")") + (max == null ? "" : " (max " + max + ")") + ".");
                }

                @Override
                public AwsException pattern(String path, String pattern) {
                    return badRequest(path + " does not match the pattern " + pattern + ".");
                }

                @Override
                public AwsException enumValue(String path, List<String> values) {
                    return badRequest(path + " must be one of " + values + ".");
                }

                @Override
                public AwsException required(String path) {
                    return badRequest(path + " is required.");
                }
            });

    final AccountAwareStorageBackend<ObjectNode> apps;
    final AccountAwareStorageBackend<ObjectNode> branches;
    private final AccountAwareStorageBackend<ObjectNode> backends;
    private final AccountAwareStorageBackend<ObjectNode> webhooks;
    private final AccountAwareStorageBackend<ObjectNode> domains;
    private final Instance<Route53Service> route53;
    final RegionResolver regionResolver;
    final ObjectMapper mapper;
    private final SecureRandom random = new SecureRandom();

    @Inject
    public AmplifyService(StorageFactory storageFactory, Instance<Route53Service> route53,
                          RegionResolver regionResolver, ObjectMapper mapper) {
        TypeReference<Map<String, ObjectNode>> type = new TypeReference<>() {};
        this.apps = storageFactory.create("amplify", "amplify-apps.json", type);
        this.branches = storageFactory.create("amplify", "amplify-branches.json", type);
        this.backends = storageFactory.create("amplify", "amplify-backend-environments.json", type);
        this.webhooks = storageFactory.create("amplify", "amplify-webhooks.json", type);
        this.domains = storageFactory.create("amplify", "amplify-domains.json", type);
        this.route53 = route53;
        this.regionResolver = regionResolver;
        this.mapper = mapper;
    }

    /** Checks a request against the model; {@code pathMembers} alternates member names and values. */
    static void check(String operation, JsonNode request, String... pathMembers) {
        ObjectNode checked = request != null && request.isObject() ? ((ObjectNode) request).deepCopy()
                : new ObjectMapper().createObjectNode();
        for (int i = 0; i + 1 < pathMembers.length; i += 2) {
            if (pathMembers[i + 1] != null) {
                checked.put(pathMembers[i], pathMembers[i + 1]);
            }
        }
        SHAPES.check(operation, checked);
    }

    // ── Apps ───────────────────────────────────────────────────────────────

    public synchronized ObjectNode createApp(JsonNode in, String region) {
        check("CreateApp", in);
        String repository = in.path("repository").asText("");
        if (!repository.isEmpty() && in.path("accessToken").asText("").isEmpty()
                && in.path("oauthToken").asText("").isEmpty()) {
            throw badRequest("An app connected to a repository needs an accessToken or an oauthToken.");
        }
        checkBasicAuth(in);
        String appId = "d" + randomText(13);
        long now = Instant.now().getEpochSecond();
        ObjectNode app = mapper.createObjectNode();
        app.put("appId", appId);
        app.put("appArn", arn(region, "apps/" + appId));
        app.put("name", in.path("name").asText());
        app.put("description", in.path("description").asText(""));
        app.put("repository", repository);
        app.put("platform", in.path("platform").asText("WEB"));
        app.put("createTime", now);
        app.put("updateTime", now);
        app.set("environmentVariables", in.path("environmentVariables").isObject()
                ? in.get("environmentVariables").deepCopy() : mapper.createObjectNode());
        app.put("defaultDomain", appId + ".amplifyapp.com");
        app.put("enableBranchAutoBuild", in.path("enableBranchAutoBuild").asBoolean(false));
        app.put("enableBranchAutoDeletion", in.path("enableBranchAutoDeletion").asBoolean(false));
        app.put("enableBasicAuth", in.path("enableBasicAuth").asBoolean(false));
        app.put("enableAutoBranchCreation", in.path("enableAutoBranchCreation").asBoolean(false));
        app.put("repositoryCloneMethod", repository.isEmpty() ? "SIGV4" : "TOKEN");
        app.set("customRules", in.path("customRules").isArray() ? in.get("customRules").deepCopy()
                : mapper.createArrayNode());
        for (String field : List.of("basicAuthCredentials", "buildSpec", "customHeaders", "computeRoleArn",
                "iamServiceRoleArn", "autoBranchCreationPatterns", "autoBranchCreationConfig")) {
            if (in.hasNonNull(field)) {
                app.set(field, in.get(field).deepCopy());
            }
        }
        app.set("cacheConfig", in.path("cacheConfig").isObject() ? in.get("cacheConfig").deepCopy()
                : mapper.createObjectNode().put("type", "AMPLIFY_MANAGED_NO_COOKIES"));
        app.set("jobConfig", in.path("jobConfig").isObject() ? in.get("jobConfig").deepCopy()
                : mapper.createObjectNode().put("buildComputeType", "STANDARD_8GB"));
        app.set("tags", tagsOf(in));
        apps.put(key(region, appId), app);
        return wrap("app", view(app));
    }

    private static void checkBasicAuth(JsonNode in) {
        if (in.path("enableBasicAuth").asBoolean(false) && in.path("basicAuthCredentials").asText("").isEmpty()) {
            throw badRequest("Basic authorization needs basicAuthCredentials.");
        }
        if (in.hasNonNull("basicAuthCredentials")) {
            try {
                String decoded = new String(Base64.getDecoder().decode(in.get("basicAuthCredentials").asText()),
                        StandardCharsets.UTF_8);
                if (!decoded.contains(":")) {
                    throw new IllegalArgumentException();
                }
            } catch (IllegalArgumentException e) {
                throw badRequest("basicAuthCredentials must be the base64 encoding of username:password.");
            }
        }
    }

    public ObjectNode getApp(String appId, String region) {
        check("GetApp", null, "appId", appId);
        return wrap("app", view(requireApp(appId, region)));
    }

    public synchronized ObjectNode updateApp(String appId, JsonNode in, String region) {
        check("UpdateApp", in, "appId", appId);
        ObjectNode app = requireApp(appId, region);
        checkBasicAuth(in);
        for (String field : List.of("name", "description", "platform", "computeRoleArn", "iamServiceRoleArn",
                "environmentVariables", "enableBranchAutoBuild", "enableBranchAutoDeletion", "enableBasicAuth",
                "basicAuthCredentials", "customRules", "buildSpec", "customHeaders", "enableAutoBranchCreation",
                "autoBranchCreationPatterns", "autoBranchCreationConfig", "repository", "cacheConfig",
                "jobConfig")) {
            if (in.has(field)) {
                app.set(field, in.get(field).deepCopy());
            }
        }
        app.put("updateTime", Instant.now().getEpochSecond());
        apps.put(key(region, appId), app);
        return wrap("app", view(app));
    }

    public synchronized ObjectNode deleteApp(String appId, String region, AmplifyHosting hosting) {
        check("DeleteApp", null, "appId", appId);
        ObjectNode app = requireApp(appId, region);
        String prefix = key(region, appId) + "::";
        for (ObjectNode branch : branches.scan(k -> k.startsWith(prefix))) {
            hosting.forgetBranch(appId, branch.path("branchName").asText(), region);
        }
        for (AccountAwareStorageBackend<ObjectNode> store : List.of(branches, backends, domains)) {
            store.keys().stream().filter(k -> k.startsWith(prefix)).toList().forEach(store::delete);
        }
        webhooks.keys().stream().filter(k -> k.startsWith(region + "::"))
                .filter(k -> webhooks.get(k).map(w -> appId.equals(w.path("appId").asText())).orElse(false))
                .toList().forEach(webhooks::delete);
        apps.delete(key(region, appId));
        return wrap("app", view(app));
    }

    public ObjectNode listApps(Integer maxResults, String nextToken, String region) {
        List<ObjectNode> all = apps.scan(k -> k.startsWith(region + "::")).stream()
                .sorted(Comparator.comparingLong(a -> a.path("createTime").asLong())).map(this::view).toList();
        ObjectNode out = mapper.createObjectNode();
        ArrayNode list = out.putArray("apps");
        page(all, maxResults, nextToken, out).forEach(list::add);
        return out;
    }

    ObjectNode requireApp(String appId, String region) {
        return apps.get(key(region, appId)).orElseThrow(() -> new AwsException("NotFoundException",
                "App " + appId + " not found.", 404));
    }

    private ObjectNode view(ObjectNode app) {
        ObjectNode copy = app.deepCopy();
        String appId = app.path("appId").asText();
        String region = AwsArnUtils.parse(app.path("appArn").asText()).region();
        branches.scan(k -> k.startsWith(key(region, appId) + "::")).stream()
                .filter(b -> "PRODUCTION".equals(b.path("stage").asText()))
                .findFirst().ifPresent(b -> {
                    ObjectNode production = copy.putObject("productionBranch");
                    production.put("branchName", b.path("branchName").asText());
                    production.put("status", b.path("activeJobId").asText("").isEmpty() ? "PENDING" : "SUCCEED");
                    if (b.has("lastDeployTime")) {
                        production.put("lastDeployTime", b.path("lastDeployTime").asLong());
                    }
                });
        return copy;
    }

    // ── Branches ───────────────────────────────────────────────────────────

    public synchronized ObjectNode createBranch(String appId, JsonNode in, String region) {
        check("CreateBranch", in, "appId", appId);
        ObjectNode app = requireApp(appId, region);
        String name = in.path("branchName").asText();
        String storeKey = branchKey(region, appId, name);
        if (branches.get(storeKey).isPresent()) {
            throw badRequest("Branch " + name + " already exists for app " + appId + ".");
        }
        checkBasicAuth(in);
        String stage = in.path("stage").asText("NONE");
        if (!"NONE".equals(stage) && !STAGES.contains(stage)) {
            throw badRequest("stage must be one of " + STAGES + ".");
        }
        if (in.hasNonNull("backendEnvironmentArn")
                && backends.scan(k -> k.startsWith(key(region, appId) + "::")).stream()
                .noneMatch(b -> b.path("backendEnvironmentArn").asText().equals(in.get("backendEnvironmentArn")
                        .asText()))) {
            throw new AwsException("NotFoundException", "Backend environment "
                    + in.get("backendEnvironmentArn").asText() + " not found.", 404);
        }
        long now = Instant.now().getEpochSecond();
        ObjectNode branch = mapper.createObjectNode();
        branch.put("branchArn", app.path("appArn").asText() + "/branches/" + URLEncoder.encode(name,
                StandardCharsets.UTF_8));
        branch.put("branchName", name);
        branch.put("description", in.path("description").asText(""));
        branch.put("stage", stage);
        branch.put("displayName", in.path("displayName").asText(name.replace('/', '-')));
        branch.put("enableNotification", in.path("enableNotification").asBoolean(false));
        branch.put("createTime", now);
        branch.put("updateTime", now);
        branch.set("environmentVariables", in.path("environmentVariables").isObject()
                ? in.get("environmentVariables").deepCopy() : mapper.createObjectNode());
        branch.put("enableAutoBuild", in.path("enableAutoBuild").asBoolean(true));
        branch.put("enableSkewProtection", in.path("enableSkewProtection").asBoolean(false));
        branch.putArray("customDomains");
        branch.put("framework", in.path("framework").asText(""));
        branch.put("activeJobId", "");
        branch.put("totalNumberOfJobs", "0");
        branch.put("enableBasicAuth", in.path("enableBasicAuth").asBoolean(false));
        branch.put("enablePerformanceMode", in.path("enablePerformanceMode").asBoolean(false));
        branch.put("ttl", in.path("ttl").asText("5"));
        branch.put("enablePullRequestPreview", in.path("enablePullRequestPreview").asBoolean(false));
        for (String field : List.of("basicAuthCredentials", "buildSpec", "pullRequestEnvironmentName",
                "backendEnvironmentArn", "backend", "computeRoleArn")) {
            if (in.hasNonNull(field)) {
                branch.set(field, in.get(field).deepCopy());
            }
        }
        branch.set("tags", tagsOf(in));
        branches.put(storeKey, branch);
        return wrap("branch", branch.deepCopy());
    }

    public ObjectNode getBranch(String appId, String name, String region) {
        check("GetBranch", null, "appId", appId, "branchName", name);
        requireApp(appId, region);
        return wrap("branch", requireBranch(appId, name, region).deepCopy());
    }

    public synchronized ObjectNode updateBranch(String appId, String name, JsonNode in, String region) {
        check("UpdateBranch", in, "appId", appId, "branchName", name);
        requireApp(appId, region);
        ObjectNode branch = requireBranch(appId, name, region);
        checkBasicAuth(in);
        for (String field : List.of("description", "framework", "stage", "enableNotification", "enableAutoBuild",
                "enableSkewProtection", "environmentVariables", "basicAuthCredentials", "enableBasicAuth",
                "enablePerformanceMode", "buildSpec", "ttl", "displayName", "enablePullRequestPreview",
                "pullRequestEnvironmentName", "backendEnvironmentArn", "backend", "computeRoleArn")) {
            if (in.has(field)) {
                branch.set(field, in.get(field).deepCopy());
            }
        }
        branch.put("updateTime", Instant.now().getEpochSecond());
        branches.put(branchKey(region, appId, name), branch);
        return wrap("branch", branch.deepCopy());
    }

    public synchronized ObjectNode deleteBranch(String appId, String name, String region, AmplifyHosting hosting) {
        check("DeleteBranch", null, "appId", appId, "branchName", name);
        requireApp(appId, region);
        ObjectNode branch = requireBranch(appId, name, region);
        hosting.forgetBranch(appId, name, region);
        branches.delete(branchKey(region, appId, name));
        return wrap("branch", branch.deepCopy());
    }

    public ObjectNode listBranches(String appId, Integer maxResults, String nextToken, String region) {
        check("ListBranches", null, "appId", appId);
        requireApp(appId, region);
        List<ObjectNode> all = branches.scan(k -> k.startsWith(key(region, appId) + "::")).stream()
                .sorted(Comparator.comparingLong(b -> b.path("createTime").asLong())).toList();
        ObjectNode out = mapper.createObjectNode();
        ArrayNode list = out.putArray("branches");
        page(all, maxResults, nextToken, out).forEach(b -> list.add(b.deepCopy()));
        return out;
    }

    ObjectNode requireBranch(String appId, String name, String region) {
        return branches.get(branchKey(region, appId, name)).orElseThrow(() -> new AwsException("NotFoundException",
                "Branch " + name + " not found for app " + appId + ".", 404));
    }

    void saveBranch(String appId, String name, String region, ObjectNode branch) {
        branches.put(branchKey(region, appId, name), branch);
    }

    // ── Backend environments ───────────────────────────────────────────────

    public synchronized ObjectNode createBackend(String appId, JsonNode in, String region) {
        check("CreateBackendEnvironment", in, "appId", appId);
        ObjectNode app = requireApp(appId, region);
        String name = in.path("environmentName").asText();
        String storeKey = key(region, appId) + "::" + name;
        if (backends.get(storeKey).isPresent()) {
            throw badRequest("Backend environment " + name + " already exists.");
        }
        long now = Instant.now().getEpochSecond();
        ObjectNode backend = mapper.createObjectNode();
        backend.put("backendEnvironmentArn", app.path("appArn").asText() + "/backendenvironments/" + name);
        backend.put("environmentName", name);
        backend.put("stackName", in.path("stackName").asText("amplify-" + appId + "-" + name));
        backend.put("deploymentArtifacts", in.path("deploymentArtifacts").asText("amplify-" + appId + "-" + name
                + "-deployment"));
        backend.put("createTime", now);
        backend.put("updateTime", now);
        backends.put(storeKey, backend);
        return wrap("backendEnvironment", backend.deepCopy());
    }

    public ObjectNode getBackend(String appId, String name, String region) {
        check("GetBackendEnvironment", null, "appId", appId, "environmentName", name);
        return wrap("backendEnvironment", requireBackend(appId, name, region).deepCopy());
    }

    public synchronized ObjectNode deleteBackend(String appId, String name, String region) {
        check("DeleteBackendEnvironment", null, "appId", appId, "environmentName", name);
        ObjectNode backend = requireBackend(appId, name, region);
        backends.delete(key(region, appId) + "::" + name);
        return wrap("backendEnvironment", backend.deepCopy());
    }

    public ObjectNode listBackends(String appId, String environmentName, Integer maxResults, String nextToken,
                                   String region) {
        check("ListBackendEnvironments", null, "appId", appId);
        requireApp(appId, region);
        List<ObjectNode> all = backends.scan(k -> k.startsWith(key(region, appId) + "::")).stream()
                .filter(b -> environmentName == null || environmentName.equals(b.path("environmentName").asText()))
                .sorted(Comparator.comparingLong(b -> b.path("createTime").asLong())).toList();
        ObjectNode out = mapper.createObjectNode();
        ArrayNode list = out.putArray("backendEnvironments");
        page(all, maxResults, nextToken, out).forEach(b -> list.add(b.deepCopy()));
        return out;
    }

    private ObjectNode requireBackend(String appId, String name, String region) {
        requireApp(appId, region);
        return backends.get(key(region, appId) + "::" + name).orElseThrow(() -> new AwsException(
                "NotFoundException", "Backend environment " + name + " not found.", 404));
    }

    // ── Webhooks ───────────────────────────────────────────────────────────

    public synchronized ObjectNode createWebhook(String appId, JsonNode in, String region) {
        check("CreateWebhook", in, "appId", appId);
        ObjectNode app = requireApp(appId, region);
        String branchName = in.path("branchName").asText();
        requireBranch(appId, branchName, region);
        String id = UUID.randomUUID().toString();
        long now = Instant.now().getEpochSecond();
        ObjectNode webhook = mapper.createObjectNode();
        webhook.put("webhookArn", app.path("appArn").asText() + "/webhooks/" + id);
        webhook.put("webhookId", id);
        webhook.put("webhookUrl", "https://webhooks.amplify." + region + "." + AwsRegions.dnsSuffixFor(region)
                + "/prod/webhooks?id=" + id + "&token=" + randomText(32));
        webhook.put("appId", appId);
        webhook.put("branchName", branchName);
        webhook.put("description", in.path("description").asText(""));
        webhook.put("createTime", now);
        webhook.put("updateTime", now);
        webhooks.put(key(region, id), webhook);
        return wrap("webhook", webhook.deepCopy());
    }

    public ObjectNode getWebhook(String id, String region) {
        check("GetWebhook", null, "webhookId", id);
        return wrap("webhook", requireWebhook(id, region).deepCopy());
    }

    public synchronized ObjectNode updateWebhook(String id, JsonNode in, String region) {
        check("UpdateWebhook", in, "webhookId", id);
        ObjectNode webhook = requireWebhook(id, region);
        if (in.hasNonNull("branchName")) {
            requireBranch(webhook.path("appId").asText(), in.get("branchName").asText(), region);
            webhook.put("branchName", in.get("branchName").asText());
        }
        if (in.has("description")) {
            webhook.put("description", in.path("description").asText(""));
        }
        webhook.put("updateTime", Instant.now().getEpochSecond());
        webhooks.put(key(region, id), webhook);
        return wrap("webhook", webhook.deepCopy());
    }

    public synchronized ObjectNode deleteWebhook(String id, String region) {
        check("DeleteWebhook", null, "webhookId", id);
        ObjectNode webhook = requireWebhook(id, region);
        webhooks.delete(key(region, id));
        return wrap("webhook", webhook.deepCopy());
    }

    public ObjectNode listWebhooks(String appId, Integer maxResults, String nextToken, String region) {
        check("ListWebhooks", null, "appId", appId);
        requireApp(appId, region);
        List<ObjectNode> all = webhooks.scan(k -> k.startsWith(region + "::")).stream()
                .filter(w -> appId.equals(w.path("appId").asText()))
                .sorted(Comparator.comparingLong(w -> w.path("createTime").asLong())).toList();
        ObjectNode out = mapper.createObjectNode();
        ArrayNode list = out.putArray("webhooks");
        page(all, maxResults, nextToken, out).forEach(w -> list.add(w.deepCopy()));
        return out;
    }

    private ObjectNode requireWebhook(String id, String region) {
        return webhooks.get(key(region, id)).orElseThrow(() -> new AwsException("NotFoundException",
                "Webhook " + id + " not found.", 404));
    }

    // ── Domain associations ────────────────────────────────────────────────

    /**
     * Associates a custom domain with branches. When the domain's public hosted zone is in Floci's
     * Route 53, Amplify verifies it as it does on AWS: it writes the certificate validation record
     * and a CNAME per subdomain to the branch's host, and the domain becomes AVAILABLE. Otherwise it
     * waits in PENDING_VERIFICATION with the records to create.
     */
    public synchronized ObjectNode createDomain(String appId, JsonNode in, String region) {
        check("CreateDomainAssociation", in, "appId", appId);
        ObjectNode app = requireApp(appId, region);
        String domainName = in.path("domainName").asText().toLowerCase(Locale.ROOT).replaceAll("\\.$", "");
        if (!DOMAIN.matcher(domainName).matches()) {
            throw badRequest("The domain name " + domainName + " is not valid.");
        }
        String storeKey = key(region, appId) + "::" + domainName;
        if (domains.get(storeKey).isPresent()) {
            throw badRequest("Domain " + domainName + " is already associated with app " + appId + ".");
        }
        ObjectNode domain = mapper.createObjectNode();
        domain.put("domainAssociationArn", app.path("appArn").asText() + "/domains/" + domainName);
        domain.put("domainName", domainName);
        domain.put("enableAutoSubDomain", in.path("enableAutoSubDomain").asBoolean(false));
        if (in.has("autoSubDomainCreationPatterns")) {
            domain.set("autoSubDomainCreationPatterns", in.get("autoSubDomainCreationPatterns").deepCopy());
        }
        if (in.hasNonNull("autoSubDomainIAMRole")) {
            domain.put("autoSubDomainIAMRole", in.get("autoSubDomainIAMRole").asText());
        }
        ObjectNode certificate = domain.putObject("certificate");
        JsonNode settings = in.path("certificateSettings");
        String certificateType = settings.path("type").asText("AMPLIFY_MANAGED");
        certificate.put("type", certificateType);
        if ("CUSTOM".equals(certificateType)) {
            if (settings.path("customCertificateArn").asText("").isEmpty()) {
                throw badRequest("A CUSTOM certificate needs a customCertificateArn.");
            }
            certificate.put("customCertificateArn", settings.get("customCertificateArn").asText());
        }
        domain.set("subDomains", subDomains(appId, domainName, in.path("subDomainSettings"), region));
        domain.set("tags", tagsOf(in));
        verify(app, domain, region);
        domains.put(storeKey, domain);
        return wrap("domainAssociation", domainView(domain));
    }

    private ArrayNode subDomains(String appId, String domainName, JsonNode settings, String region) {
        if (!settings.isArray() || settings.isEmpty()) {
            throw badRequest("A domain association needs at least one subDomainSetting.");
        }
        ArrayNode out = mapper.createArrayNode();
        for (JsonNode setting : settings) {
            requireBranch(appId, setting.path("branchName").asText(), region);
            ObjectNode sub = out.addObject();
            sub.set("subDomainSetting", setting.deepCopy());
            sub.put("verified", false);
            String prefix = setting.path("prefix").asText("");
            sub.put("dnsRecord", prefix + " CNAME " + branchHost(appId, setting.path("branchName").asText(), region));
        }
        return out;
    }

    private void verify(ObjectNode app, ObjectNode domain, String region) {
        String domainName = domain.path("domainName").asText();
        String validation = "_" + randomText(32) + "." + domainName + ". CNAME _" + randomText(32)
                + ".acm-validations.aws.";
        domain.put("certificateVerificationDNSRecord", validation);
        domain.withObject("certificate").put("certificateVerificationDNSRecord", validation);
        Optional<HostedZone> zone = zoneFor(domainName);
        if (zone.isEmpty()) {
            domain.put("domainStatus", "PENDING_VERIFICATION");
            domain.put("statusReason", "Create the verification and subdomain records in the domain's DNS: there "
                    + "is no hosted zone for " + domainName + " in Route 53.");
            return;
        }
        String appId = app.path("appId").asText();
        List<Map<String, Object>> changes = new ArrayList<>();
        String[] parts = validation.split(" CNAME ");
        changes.add(Map.of("action", "UPSERT", "rrs", record(parts[0], parts[1])));
        for (JsonNode sub : domain.withArray("subDomains")) {
            String prefix = sub.path("subDomainSetting").path("prefix").asText("");
            String target = branchHost(appId, sub.path("subDomainSetting").path("branchName").asText(), region);
            if (!prefix.isEmpty()) {
                changes.add(Map.of("action", "UPSERT", "rrs", record(prefix + "." + domainName, target)));
            }
            ((ObjectNode) sub).put("verified", true);
        }
        route53.get().changeResourceRecordSets(zone.get().getId(), changes, "Amplify domain association");
        domain.put("domainStatus", "AVAILABLE");
        domain.put("updateStatus", "UPDATE_COMPLETE");
        domain.put("statusReason", "");
        for (JsonNode sub : domain.withArray("subDomains")) {
            String branchName = sub.path("subDomainSetting").path("branchName").asText();
            ObjectNode branch = branches.get(branchKey(region, appId, branchName)).orElse(null);
            if (branch == null) {
                continue;
            }
            String prefix = sub.path("subDomainSetting").path("prefix").asText("");
            String host = prefix.isEmpty() ? domainName : prefix + "." + domainName;
            ArrayNode custom = branch.withArray("customDomains");
            boolean present = false;
            for (JsonNode existing : custom) {
                present |= existing.asText().equals(host);
            }
            if (!present) {
                custom.add(host);
            }
            saveBranch(appId, branchName, region, branch);
        }
    }

    private Optional<HostedZone> zoneFor(String domainName) {
        if (!route53.isResolvable()) {
            return Optional.empty();
        }
        String wanted = domainName + ".";
        return route53.get().listHostedZonesByName(wanted, 1).stream()
                .filter(z -> z.getName().equalsIgnoreCase(wanted)).findFirst();
    }

    private static ResourceRecordSet record(String name, String value) {
        ResourceRecordSet set = new ResourceRecordSet();
        set.setName(name);
        set.setType("CNAME");
        set.setTtl(300L);
        ResourceRecord record = new ResourceRecord();
        record.setValue(value);
        set.setRecords(List.of(record));
        return set;
    }

    public ObjectNode getDomain(String appId, String domainName, String region) {
        check("GetDomainAssociation", null, "appId", appId, "domainName", domainName);
        return wrap("domainAssociation", domainView(requireDomain(appId, domainName, region)));
    }

    public synchronized ObjectNode updateDomain(String appId, String domainName, JsonNode in, String region) {
        check("UpdateDomainAssociation", in, "appId", appId, "domainName", domainName);
        ObjectNode app = requireApp(appId, region);
        ObjectNode domain = requireDomain(appId, domainName, region);
        if (in.has("enableAutoSubDomain")) {
            domain.put("enableAutoSubDomain", in.get("enableAutoSubDomain").asBoolean());
        }
        if (in.has("autoSubDomainCreationPatterns")) {
            domain.set("autoSubDomainCreationPatterns", in.get("autoSubDomainCreationPatterns").deepCopy());
        }
        if (in.has("subDomainSettings")) {
            domain.set("subDomains", subDomains(appId, domain.path("domainName").asText(),
                    in.get("subDomainSettings"), region));
            verify(app, domain, region);
        }
        domains.put(key(region, appId) + "::" + domain.path("domainName").asText(), domain);
        return wrap("domainAssociation", domainView(domain));
    }

    public synchronized ObjectNode deleteDomain(String appId, String domainName, String region) {
        check("DeleteDomainAssociation", null, "appId", appId, "domainName", domainName);
        ObjectNode domain = requireDomain(appId, domainName, region);
        String name = domain.path("domainName").asText();
        domains.delete(key(region, appId) + "::" + name);
        for (ObjectNode branch : branches.scan(k -> k.startsWith(key(region, appId) + "::"))) {
            ArrayNode kept = mapper.createArrayNode();
            branch.withArray("customDomains").forEach(host -> {
                if (!host.asText().equals(name) && !host.asText().endsWith("." + name)) {
                    kept.add(host);
                }
            });
            branch.set("customDomains", kept);
            saveBranch(appId, branch.path("branchName").asText(), region, branch);
        }
        return wrap("domainAssociation", domainView(domain));
    }

    public ObjectNode listDomains(String appId, Integer maxResults, String nextToken, String region) {
        check("ListDomainAssociations", null, "appId", appId);
        requireApp(appId, region);
        List<ObjectNode> all = domains.scan(k -> k.startsWith(key(region, appId) + "::")).stream()
                .sorted(Comparator.comparing(d -> d.path("domainName").asText())).toList();
        ObjectNode out = mapper.createObjectNode();
        ArrayNode list = out.putArray("domainAssociations");
        page(all, maxResults, nextToken, out).forEach(d -> list.add(domainView(d)));
        return out;
    }

    private ObjectNode requireDomain(String appId, String domainName, String region) {
        requireApp(appId, region);
        String name = domainName.toLowerCase(Locale.ROOT).replaceAll("\\.$", "");
        return domains.get(key(region, appId) + "::" + name).orElseThrow(() -> new AwsException(
                "NotFoundException", "Domain association " + domainName + " not found.", 404));
    }

    private ObjectNode domainView(ObjectNode domain) {
        ObjectNode copy = domain.deepCopy();
        copy.remove("tags");
        return copy;
    }

    /** The account, Region, app and branch a custom domain host is served from, or null. */
    String[] branchForCustomHost(String host) {
        for (AccountAwareStorageBackend.AccountEntry<ObjectNode> entry : branches.scanAllAccountEntries(k -> true)) {
            for (JsonNode custom : entry.value().withArray("customDomains")) {
                if (custom.asText().equalsIgnoreCase(host)) {
                    AwsArnUtils.Arn arn = AwsArnUtils.parse(entry.value().path("branchArn").asText());
                    return new String[]{entry.accountId(), arn.region(), arn.resource().split("/")[1],
                            entry.value().path("branchName").asText()};
                }
            }
        }
        return null;
    }

    /** The account and Region of the app with this id, or null. */
    String[] locateApp(String appId) {
        for (AccountAwareStorageBackend.AccountEntry<ObjectNode> entry
                : apps.scanAllAccountEntries(k -> k.endsWith("::" + appId))) {
            return new String[]{entry.accountId(), AwsArnUtils.parse(entry.value().path("appArn").asText()).region()};
        }
        return null;
    }

    /** The branch of an app whose host label ({@code branch name with '/' as '-'}) is {@code label}. */
    Optional<ObjectNode> branchByLabel(String appId, String label, String accountId, String region) {
        String prefix = key(region, appId) + "::";
        for (AccountAwareStorageBackend.AccountEntry<ObjectNode> entry
                : branches.scanAllAccountEntries(k -> k.startsWith(prefix))) {
            if (entry.accountId().equals(accountId) && entry.value().path("branchName").asText().replace('/', '-')
                    .equalsIgnoreCase(label)) {
                return Optional.of(entry.value());
            }
        }
        return Optional.empty();
    }

    // ── Tags ───────────────────────────────────────────────────────────────

    /** The stored app, branch or domain association an Amplify ARN names, with its store and key. */
    private record Tagged(AccountAwareStorageBackend<ObjectNode> store, String key, ObjectNode resource) {}

    private Tagged tagged(String arn, String region) {
        if (!AwsArnUtils.isArnFor(arn, "amplify")) {
            throw badRequest("The resource ARN " + arn + " is not an Amplify ARN.");
        }
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        String arnRegion = parsed.region().isEmpty() ? region : parsed.region();
        String[] parts = parsed.resource().split("/", 4);
        if (parts.length >= 2 && "apps".equals(parts[0])) {
            String appKey = key(arnRegion, parts[1]);
            if (parts.length == 2) {
                return apps.get(appKey).map(a -> new Tagged(apps, appKey, a)).orElseThrow(() -> notFound(arn));
            }
            if (parts.length == 4 && "branches".equals(parts[2])) {
                String storeKey = appKey + "::" + URLDecoder.decode(parts[3], StandardCharsets.UTF_8);
                return branches.get(storeKey).map(b -> new Tagged(branches, storeKey, b))
                        .orElseThrow(() -> notFound(arn));
            }
            if (parts.length == 4 && "domains".equals(parts[2])) {
                String storeKey = appKey + "::" + parts[3];
                return domains.get(storeKey).map(d -> new Tagged(domains, storeKey, d))
                        .orElseThrow(() -> notFound(arn));
            }
        }
        throw notFound(arn);
    }

    private static AwsException notFound(String arn) {
        return new AwsException("ResourceNotFoundException", "Resource " + arn + " not found.", 404);
    }

    public Map<String, String> listTags(String arn, String region) {
        Map<String, String> out = new LinkedHashMap<>();
        tagged(arn, region).resource().path("tags").fields().forEachRemaining(e -> out.put(e.getKey(),
                e.getValue().asText()));
        return out;
    }

    public synchronized void tag(String arn, Map<String, String> tags, String region) {
        Tagged tagged = tagged(arn, region);
        ObjectNode current = tagged.resource().withObject("tags");
        tags.forEach(current::put);
        if (current.size() > 50) {
            throw badRequest("A resource can have at most 50 tags.");
        }
        tagged.store().put(tagged.key(), tagged.resource());
    }

    public synchronized void untag(String arn, List<String> keys, String region) {
        Tagged tagged = tagged(arn, region);
        keys.forEach(tagged.resource().withObject("tags")::remove);
        tagged.store().put(tagged.key(), tagged.resource());
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    /** The host a branch is served at: {@code <branch>.<defaultDomain>}. */
    String branchHost(String appId, String branchName, String region) {
        String domain = apps.get(key(region, appId)).map(a -> a.path("defaultDomain").asText())
                .orElse(appId + ".amplifyapp.com");
        return branchName.replace('/', '-').toLowerCase(Locale.ROOT) + "." + domain;
    }

    private ObjectNode tagsOf(JsonNode in) {
        ObjectNode tags = mapper.createObjectNode();
        in.path("tags").fields().forEachRemaining(e -> tags.put(e.getKey(), e.getValue().asText()));
        return tags;
    }

    ObjectNode wrap(String field, ObjectNode value) {
        ObjectNode out = mapper.createObjectNode();
        out.set(field, value);
        return out;
    }

    static List<ObjectNode> page(List<ObjectNode> all, Integer maxResults, String nextToken, ObjectNode out) {
        int max = maxResults == null ? 100 : maxResults;
        if (max < 1 || max > 100) {
            throw badRequest("maxResults must be between 1 and 100.");
        }
        int start = 0;
        if (nextToken != null && !nextToken.isEmpty()) {
            try {
                start = Integer.parseInt(new String(Base64.getDecoder().decode(nextToken), StandardCharsets.UTF_8));
            } catch (RuntimeException e) {
                throw badRequest("The nextToken is not valid.");
            }
        }
        int end = Math.min(all.size(), start + max);
        if (end < all.size()) {
            out.put("nextToken", Base64.getEncoder().encodeToString(Integer.toString(end)
                    .getBytes(StandardCharsets.UTF_8)));
        }
        return all.subList(Math.min(start, end), end);
    }

    String arn(String region, String resource) {
        return AwsArnUtils.Arn.of("amplify", region, regionResolver.getAccountId(), resource).toString();
    }

    String randomText(int length) {
        StringBuilder out = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            out.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return out.toString();
    }

    static String key(String region, String appId) {
        return region + "::" + appId;
    }

    static String branchKey(String region, String appId, String branch) {
        return region + "::" + appId + "::" + branch;
    }

    static AwsException badRequest(String message) {
        return new AwsException("BadRequestException", message, 400);
    }

    @Override
    public void clear() {
        apps.clear();
        branches.clear();
        backends.clear();
        webhooks.clear();
        domains.clear();
    }
}
