package io.github.hectorvent.floci.services.amplify;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.amplify.model.AmplifyApp;
import io.github.hectorvent.floci.services.amplify.model.AmplifyBranch;
import io.github.hectorvent.floci.services.amplify.model.AmplifyJob;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import io.github.hectorvent.floci.services.s3.PreSignedUrlGenerator;
import io.github.hectorvent.floci.services.s3.S3Service;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Amplify Hosting restJson1 — app, branch, and manual-deploy job lifecycle plus resource tags.
 *
 * <p>Tag APIs share {@code /tags/{arn}} and are dispatched by {@code SharedTagsController}
 * using ARN service {@code amplify}.
 */
@ApplicationScoped
public class AmplifyService implements TagHandler {

    private static final Logger LOG = Logger.getLogger(AmplifyService.class);
    static final String SERVICE = "amplify";
    private static final String TOKEN_PREFIX = "amplify:v1:";
    private static final int DEFAULT_MAX_RESULTS = 100;
    private static final int MAX_RESULTS = 100;
    private static final int ZIP_UPLOAD_EXPIRY_SECONDS = 8 * 60 * 60;
    private static final char[] APP_ID_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<String> PLATFORMS = Set.of("WEB", "WEB_DYNAMIC", "WEB_COMPUTE");
    private static final Set<String> STAGES = Set.of(
            "PRODUCTION", "BETA", "DEVELOPMENT", "EXPERIMENTAL", "PULL_REQUEST", "NONE");
    private static final Set<String> TERMINAL_JOB_STATUSES = Set.of("SUCCEED", "FAILED", "CANCELLED");
    private static final Set<String> REPO_JOB_TYPES = Set.of("RELEASE", "RETRY", "WEB_HOOK");
    private static final Set<String> JOB_TYPES = Set.of("RELEASE", "RETRY", "MANUAL", "WEB_HOOK");
    private static final String DEPLOYMENT_BUCKET = "amplify-deployment-uploads";
    private static final String HTTPS_MARKER = "x-amz-amplify=https://console.aws.amazon.com/amplify";

    private final StorageBackend<String, AmplifyApp> store;
    private final RegionResolver regionResolver;
    private final EmulatorConfig config;
    private final PreSignedUrlGenerator presignGenerator;
    private final S3Service s3Service;
    private final Instance<EventBridgeService> eventBridgeService;

    @Inject
    public AmplifyService(
            StorageFactory storageFactory,
            RegionResolver regionResolver,
            EmulatorConfig config,
            PreSignedUrlGenerator presignGenerator,
            S3Service s3Service,
            Instance<EventBridgeService> eventBridgeService) {
        this(storageFactory.create(
                "amplify",
                "amplify-apps.json",
                new TypeReference<Map<String, AmplifyApp>>() {
                }), regionResolver, config, presignGenerator, s3Service, eventBridgeService);
    }

    AmplifyService(StorageBackend<String, AmplifyApp> store, RegionResolver regionResolver) {
        this(store, regionResolver, null, null, null, null);
    }

    AmplifyService(StorageBackend<String, AmplifyApp> store) {
        this(store, new RegionResolver("us-east-1", "000000000000"));
    }

    AmplifyService(
            StorageBackend<String, AmplifyApp> store,
            RegionResolver regionResolver,
            EmulatorConfig config,
            PreSignedUrlGenerator presignGenerator,
            S3Service s3Service,
            Instance<EventBridgeService> eventBridgeService) {
        this.store = store;
        this.regionResolver = regionResolver;
        this.config = config;
        this.presignGenerator = presignGenerator;
        this.s3Service = s3Service;
        this.eventBridgeService = eventBridgeService;
    }

    public synchronized AmplifyApp createApp(String region, JsonNode request) {
        requireObject(request, "Request body");
        String name = requireText(request, "name");
        String platform = optionalText(request, "platform");
        if (platform == null) {
            platform = "WEB";
        }
        validatePlatform(platform);

        String appId = newAppId();
        while (store.get(storageKey(region, appId)).isPresent()) {
            appId = newAppId();
        }
        long now = now();
        AmplifyApp app = new AmplifyApp();
        app.setAppId(appId);
        app.setAppArn(appArn(region, appId));
        app.setName(name);
        app.setDescription(optionalText(request, "description"));
        app.setPlatform(platform);
        app.setCreateTime(now);
        app.setUpdateTime(now);
        app.setTags(readStringMap(request, "tags"));
        app.setEnvironmentVariables(readStringMap(request, "environmentVariables"));
        app.setDefaultDomain(appId + ".amplifyapp.com");
        app.setEnableBranchAutoBuild(booleanOrDefault(request, "enableBranchAutoBuild", false));
        app.setEnableBranchAutoDeletion(booleanOrDefault(request, "enableBranchAutoDeletion", false));
        app.setEnableBasicAuth(booleanOrDefault(request, "enableBasicAuth", false));
        app.setBasicAuthCredentials(optionalText(request, "basicAuthCredentials"));
        app.setBuildSpec(optionalText(request, "buildSpec"));
        app.setCustomHeaders(optionalText(request, "customHeaders"));
        app.setEnableAutoBranchCreation(booleanOrDefault(request, "enableAutoBranchCreation", false));
        app.setComputeRoleArn(optionalText(request, "computeRoleArn"));
        app.setIamServiceRoleArn(optionalText(request, "iamServiceRoleArn"));
        String repository = optionalText(request, "repository");
        app.setRepository(repository == null ? "" : repository);
        if (request.has("customRules") && !request.get("customRules").isNull()) {
            app.setCustomRules(request.get("customRules"));
        }
        app.setBranches(new LinkedHashMap<>());
        store.put(storageKey(region, appId), app);
        return app;
    }

    public AmplifyApp getApp(String region, String appId) {
        return requireApp(region, appId);
    }

    public synchronized AmplifyApp updateApp(String region, String appId, JsonNode request) {
        requireObject(request, "Request body");
        AmplifyApp app = requireApp(region, appId);
        if (request.has("name") && !request.get("name").isNull()) {
            app.setName(requireText(request, "name"));
        }
        if (request.has("description")) {
            app.setDescription(textOrNull(request, "description"));
        }
        if (request.has("platform") && !request.get("platform").isNull()) {
            String platform = requireText(request, "platform");
            validatePlatform(platform);
            app.setPlatform(platform);
        }
        if (request.has("environmentVariables")) {
            app.setEnvironmentVariables(readStringMap(request, "environmentVariables"));
        }
        if (request.has("enableBranchAutoBuild")) {
            app.setEnableBranchAutoBuild(optionalBoolean(request, "enableBranchAutoBuild"));
        }
        if (request.has("enableBranchAutoDeletion")) {
            app.setEnableBranchAutoDeletion(optionalBoolean(request, "enableBranchAutoDeletion"));
        }
        if (request.has("enableBasicAuth")) {
            app.setEnableBasicAuth(optionalBoolean(request, "enableBasicAuth"));
        }
        if (request.has("basicAuthCredentials")) {
            app.setBasicAuthCredentials(textOrNull(request, "basicAuthCredentials"));
        }
        if (request.has("buildSpec")) {
            app.setBuildSpec(textOrNull(request, "buildSpec"));
        }
        if (request.has("customHeaders")) {
            app.setCustomHeaders(textOrNull(request, "customHeaders"));
        }
        if (request.has("enableAutoBranchCreation")) {
            app.setEnableAutoBranchCreation(optionalBoolean(request, "enableAutoBranchCreation"));
        }
        if (request.has("computeRoleArn")) {
            app.setComputeRoleArn(textOrNull(request, "computeRoleArn"));
        }
        if (request.has("iamServiceRoleArn")) {
            app.setIamServiceRoleArn(textOrNull(request, "iamServiceRoleArn"));
        }
        if (request.has("repository")) {
            app.setRepository(textOrNull(request, "repository"));
        }
        if (request.has("customRules")) {
            JsonNode rules = request.get("customRules");
            app.setCustomRules(rules == null || rules.isNull() ? null : rules);
        }
        app.setUpdateTime(now());
        store.put(storageKey(region, app.getAppId()), app);
        return app;
    }

    public synchronized AmplifyApp deleteApp(String region, String appId) {
        AmplifyApp app = requireApp(region, appId);
        store.delete(storageKey(region, app.getAppId()));
        return app;
    }

    public Page<AmplifyApp> listApps(String region, String maxResultsValue, String nextToken) {
        int maxResults = parseMaxResults(maxResultsValue);
        List<AmplifyApp> apps = store.scan(key -> key.startsWith(region + "::"));
        apps.sort(Comparator.comparing(AmplifyApp::getName, Comparator.nullsLast(String::compareTo))
                .thenComparing(AmplifyApp::getAppId));
        return page(apps, maxResults, nextToken);
    }

    public synchronized AmplifyBranch createBranch(String region, String appId, JsonNode request) {
        requireObject(request, "Request body");
        AmplifyApp app = requireApp(region, appId);
        String branchName = requireText(request, "branchName");
        if (app.getBranches().containsKey(branchName)) {
            throw new AwsException("BadRequestException",
                    "Branch " + branchName + " already exists.", 400);
        }
        long now = now();
        AmplifyBranch branch = new AmplifyBranch();
        branch.setBranchName(branchName);
        branch.setBranchArn(branchArn(region, app.getAppId(), branchName));
        branch.setDescription(optionalText(request, "description"));
        branch.setTags(readStringMap(request, "tags"));
        String stage = optionalText(request, "stage");
        if (stage == null) {
            stage = "NONE";
        }
        validateStage(stage);
        branch.setStage(stage);
        String displayName = optionalText(request, "displayName");
        branch.setDisplayName(displayName == null ? branchName : displayName);
        branch.setEnableNotification(booleanOrDefault(request, "enableNotification", false));
        branch.setCreateTime(now);
        branch.setUpdateTime(now);
        branch.setEnvironmentVariables(readStringMap(request, "environmentVariables"));
        branch.setEnableAutoBuild(booleanOrDefault(request, "enableAutoBuild", true));
        branch.setEnableSkewProtection(booleanOrDefault(request, "enableSkewProtection", false));
        branch.setFramework(optionalText(request, "framework"));
        branch.setEnableBasicAuth(booleanOrDefault(request, "enableBasicAuth", false));
        branch.setEnablePerformanceMode(booleanOrDefault(request, "enablePerformanceMode", false));
        branch.setBasicAuthCredentials(optionalText(request, "basicAuthCredentials"));
        branch.setBuildSpec(optionalText(request, "buildSpec"));
        String ttl = optionalText(request, "ttl");
        branch.setTtl(ttl == null ? "5" : ttl);
        branch.setEnablePullRequestPreview(booleanOrDefault(request, "enablePullRequestPreview", false));
        branch.setPullRequestEnvironmentName(optionalText(request, "pullRequestEnvironmentName"));
        branch.setBackendEnvironmentArn(optionalText(request, "backendEnvironmentArn"));
        branch.setComputeRoleArn(optionalText(request, "computeRoleArn"));
        branch.setActiveJobId("None");
        branch.setTotalNumberOfJobs("0");
        app.getBranches().put(branchName, branch);
        app.setUpdateTime(now);
        store.put(storageKey(region, app.getAppId()), app);
        return branch;
    }

    public AmplifyBranch getBranch(String region, String appId, String branchName) {
        return requireBranch(requireApp(region, appId), branchName);
    }

    public synchronized AmplifyBranch updateBranch(String region, String appId, String branchName, JsonNode request) {
        requireObject(request, "Request body");
        AmplifyApp app = requireApp(region, appId);
        AmplifyBranch branch = requireBranch(app, branchName);
        if (request.has("description")) {
            branch.setDescription(textOrNull(request, "description"));
        }
        if (request.has("stage") && !request.get("stage").isNull()) {
            String stage = requireText(request, "stage");
            validateStage(stage);
            branch.setStage(stage);
        }
        if (request.has("displayName")) {
            branch.setDisplayName(textOrNull(request, "displayName"));
        }
        if (request.has("enableNotification")) {
            branch.setEnableNotification(optionalBoolean(request, "enableNotification"));
        }
        if (request.has("environmentVariables")) {
            branch.setEnvironmentVariables(readStringMap(request, "environmentVariables"));
        }
        if (request.has("enableAutoBuild")) {
            branch.setEnableAutoBuild(optionalBoolean(request, "enableAutoBuild"));
        }
        if (request.has("enableSkewProtection")) {
            branch.setEnableSkewProtection(optionalBoolean(request, "enableSkewProtection"));
        }
        if (request.has("framework")) {
            branch.setFramework(textOrNull(request, "framework"));
        }
        if (request.has("enableBasicAuth")) {
            branch.setEnableBasicAuth(optionalBoolean(request, "enableBasicAuth"));
        }
        if (request.has("enablePerformanceMode")) {
            branch.setEnablePerformanceMode(optionalBoolean(request, "enablePerformanceMode"));
        }
        if (request.has("basicAuthCredentials")) {
            branch.setBasicAuthCredentials(textOrNull(request, "basicAuthCredentials"));
        }
        if (request.has("buildSpec")) {
            branch.setBuildSpec(textOrNull(request, "buildSpec"));
        }
        if (request.has("ttl")) {
            String ttl = textOrNull(request, "ttl");
            branch.setTtl(ttl == null ? "5" : ttl);
        }
        if (request.has("enablePullRequestPreview")) {
            branch.setEnablePullRequestPreview(optionalBoolean(request, "enablePullRequestPreview"));
        }
        if (request.has("pullRequestEnvironmentName")) {
            branch.setPullRequestEnvironmentName(textOrNull(request, "pullRequestEnvironmentName"));
        }
        if (request.has("backendEnvironmentArn")) {
            branch.setBackendEnvironmentArn(textOrNull(request, "backendEnvironmentArn"));
        }
        if (request.has("computeRoleArn")) {
            branch.setComputeRoleArn(textOrNull(request, "computeRoleArn"));
        }
        long now = now();
        branch.setUpdateTime(now);
        app.getBranches().put(branch.getBranchName(), branch);
        app.setUpdateTime(now);
        store.put(storageKey(region, app.getAppId()), app);
        return branch;
    }

    public synchronized AmplifyBranch deleteBranch(String region, String appId, String branchName) {
        AmplifyApp app = requireApp(region, appId);
        AmplifyBranch branch = requireBranch(app, branchName);
        app.getBranches().remove(branch.getBranchName());
        app.setUpdateTime(now());
        store.put(storageKey(region, app.getAppId()), app);
        return branch;
    }

    public Page<AmplifyBranch> listBranches(String region, String appId, String maxResultsValue, String nextToken) {
        AmplifyApp app = requireApp(region, appId);
        List<AmplifyBranch> branches = new ArrayList<>(app.getBranches().values());
        branches.sort(Comparator.comparing(AmplifyBranch::getBranchName, Comparator.nullsLast(String::compareTo)));
        return page(branches, parseMaxResults(maxResultsValue), nextToken);
    }

    public synchronized CreateDeploymentOutcome createDeployment(
            String region, String appId, String branchName, JsonNode request) {
        requireObject(request, "Request body");
        AmplifyApp app = requireApp(region, appId);
        AmplifyBranch branch = requireBranch(app, branchName);
        AmplifyJob job = newJob(region, app, branch, "MANUAL");
        String zipKey = zipObjectKey(app.getAppId(), branch.getBranchName(), job.getJobId());
        job.setZipObjectKey(zipKey);
        job.setSourceUrlType("ZIP");
        Map<String, String> fileUploadUrls = new LinkedHashMap<>();
        JsonNode fileMap = request.get("fileMap");
        if (fileMap != null && fileMap.isObject()) {
            fileMap.fields().forEachRemaining(entry -> {
                String fileName = entry.getKey();
                if (fileName == null || fileName.isBlank()) {
                    return;
                }
                String fileKey = app.getAppId() + "/" + branch.getBranchName() + "/"
                        + job.getJobId() + "/files/" + fileName.replace('\\', '/');
                fileUploadUrls.put(fileName, presignPut(region, fileKey));
            });
        }
        persist(region, app, branch);
        return new CreateDeploymentOutcome(job, presignPut(region, zipKey), fileUploadUrls);
    }

    public synchronized AmplifyJob startDeployment(
            String region, String appId, String branchName, JsonNode request) {
        requireObject(request, "Request body");
        AmplifyApp app = requireApp(region, appId);
        AmplifyBranch branch = requireBranch(app, branchName);
        String jobId = optionalText(request, "jobId");
        String sourceUrl = optionalText(request, "sourceUrl");
        AmplifyJob job;
        if (jobId != null) {
            job = requireJob(branch, jobId);
            if (now() - job.getStartTime() > ZIP_UPLOAD_EXPIRY_SECONDS) {
                throw new AwsException("BadRequestException",
                        "The maximum duration between CreateDeployment and StartDeployment is 8 hours.", 400);
            }
        } else if (sourceUrl != null) {
            job = newJob(region, app, branch, "MANUAL");
            job.setSourceUrl(sourceUrl);
            String sourceUrlType = optionalText(request, "sourceUrlType");
            job.setSourceUrlType(sourceUrlType == null ? "ZIP" : sourceUrlType);
        } else {
            throw new AwsException("BadRequestException",
                    "jobId or sourceUrl is required.", 400);
        }
        if (TERMINAL_JOB_STATUSES.contains(job.getStatus())) {
            return job;
        }
        completeJob(region, app, branch, job, "SUCCEED");
        return job;
    }

    public synchronized AmplifyJob startJob(
            String region, String appId, String branchName, JsonNode request) {
        requireObject(request, "Request body");
        AmplifyApp app = requireApp(region, appId);
        AmplifyBranch branch = requireBranch(app, branchName);
        String jobType = requireText(request, "jobType");
        if (!JOB_TYPES.contains(jobType)) {
            throw new AwsException("BadRequestException", "jobType is invalid.", 400);
        }
        if (REPO_JOB_TYPES.contains(jobType) && !hasRepository(app)) {
            throw new AwsException("BadRequestException",
                    "A " + jobType + " job requires a connected Git repository.", 400);
        }
        AmplifyJob job = newJob(region, app, branch, jobType);
        String commitId = optionalText(request, "commitId");
        job.setCommitId(commitId == null ? "HEAD" : commitId);
        job.setCommitMessage(optionalText(request, "commitMessage"));
        completeJob(region, app, branch, job, "SUCCEED");
        return job;
    }

    public AmplifyJob getJob(String region, String appId, String branchName, String jobId) {
        return requireJob(requireBranch(requireApp(region, appId), branchName), jobId);
    }

    public Page<AmplifyJob> listJobs(String region, String appId, String branchName,
                                     String maxResultsValue, String nextToken) {
        AmplifyBranch branch = requireBranch(requireApp(region, appId), branchName);
        List<AmplifyJob> jobs = new ArrayList<>(branch.getJobs().values());
        jobs.sort(Comparator.comparing(AmplifyJob::getStartTime).reversed()
                .thenComparing(AmplifyJob::getJobId, Comparator.reverseOrder()));
        return page(jobs, parseMaxResults(maxResultsValue), nextToken);
    }

    public synchronized AmplifyJob stopJob(String region, String appId, String branchName, String jobId) {
        AmplifyApp app = requireApp(region, appId);
        AmplifyBranch branch = requireBranch(app, branchName);
        AmplifyJob job = requireJob(branch, jobId);
        if (TERMINAL_JOB_STATUSES.contains(job.getStatus())) {
            throw new AwsException("BadRequestException",
                    "Job " + job.getJobId() + " is already in a terminal state.", 400);
        }
        completeJob(region, app, branch, job, "CANCELLED");
        return job;
    }

    public synchronized AmplifyJob deleteJob(String region, String appId, String branchName, String jobId) {
        AmplifyApp app = requireApp(region, appId);
        AmplifyBranch branch = requireBranch(app, branchName);
        AmplifyJob job = requireJob(branch, jobId);
        if (job.getJobId().equals(branch.getActiveJobId())) {
            throw new AwsException("BadRequestException",
                    "Cannot delete the active job for this branch. Deploy a new version to the branch and then retry.",
                    400);
        }
        branch.getJobs().remove(job.getJobId());
        branch.setTotalNumberOfJobs(Integer.toString(branch.getJobs().size()));
        persist(region, app, branch);
        return job;
    }

    public Page<AmplifyJob.AmplifyArtifact> listArtifacts(
            String region, String appId, String branchName, String jobId,
            String maxResultsValue, String nextToken) {
        AmplifyJob job = requireJob(requireBranch(requireApp(region, appId), branchName), jobId);
        List<AmplifyJob.AmplifyArtifact> artifacts = new ArrayList<>(job.getArtifacts());
        artifacts.sort(Comparator.comparing(AmplifyJob.AmplifyArtifact::getArtifactFileName,
                Comparator.nullsLast(String::compareTo)));
        return page(artifacts, parseMaxResults(maxResultsValue), nextToken);
    }

    public AmplifyJob.AmplifyArtifact getArtifact(String region, String artifactId) {
        String id = decode(artifactId);
        for (AmplifyApp app : store.scan(key -> key.startsWith(region + "::"))) {
            for (AmplifyBranch branch : app.getBranches().values()) {
                for (AmplifyJob job : branch.getJobs().values()) {
                    for (AmplifyJob.AmplifyArtifact artifact : job.getArtifacts()) {
                        if (id.equals(artifact.getArtifactId())) {
                            if (artifact.getArtifactUrl() == null || artifact.getArtifactUrl().isBlank()) {
                                artifact.setArtifactUrl(presignGet(region,
                                        app.getAppId() + "/artifacts/" + artifact.getArtifactId()));
                            }
                            return artifact;
                        }
                    }
                }
            }
        }
        throw notFound("Artifact " + id + " not found.");
    }

    public synchronized String generateAccessLogs(String region, String appId, JsonNode request) {
        requireObject(request, "Request body");
        AmplifyApp app = requireApp(region, appId);
        String domainName = requireText(request, "domainName");
        String key = app.getAppId() + "/access-logs/" + domainName + "/" + now() + ".log";
        ensureDeploymentBucket(region);
        if (s3Service != null) {
            s3Service.putObject(DEPLOYMENT_BUCKET, key, new byte[0], "text/plain", Map.of());
        }
        return presignGet(region, key);
    }

    @Override
    public String serviceKey() {
        return SERVICE;
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        TaggedResource resource = requireTagged(region, arn);
        return Map.copyOf(resource.tags());
    }

    @Override
    public synchronized void tagResource(String region, String arn, Map<String, String> tags) {
        TaggedResource resource = requireTagged(region, arn);
        Map<String, String> current = new LinkedHashMap<>(resource.tags());
        if (tags != null) {
            current.putAll(tags);
        }
        resource.applyTags(current);
        store.put(storageKey(region, resource.app().getAppId()), resource.app());
    }

    @Override
    public synchronized void untagResource(String region, String arn, List<String> tagKeys) {
        TaggedResource resource = requireTagged(region, arn);
        Map<String, String> current = new LinkedHashMap<>(resource.tags());
        if (tagKeys != null) {
            tagKeys.forEach(current::remove);
        }
        resource.applyTags(current);
        store.put(storageKey(region, resource.app().getAppId()), resource.app());
    }

    private AmplifyApp requireApp(String region, String appId) {
        String id = decode(appId);
        return store.get(storageKey(region, id)).orElseThrow(() -> notFound("App " + id + " not found."));
    }

    private static AmplifyBranch requireBranch(AmplifyApp app, String branchName) {
        String name = decode(branchName);
        AmplifyBranch branch = app.getBranches().get(name);
        if (branch == null) {
            throw notFound("Branch " + name + " not found.");
        }
        return branch;
    }

    private TaggedResource requireTagged(String region, String arn) {
        String decoded = decode(arn);
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(decoded);
        } catch (IllegalArgumentException e) {
            throw new AwsException("BadRequestException", "Invalid resource ARN: " + decoded, 400);
        }
        if (!SERVICE.equals(parsed.service())) {
            throw resourceNotFound(decoded);
        }
        String resource = parsed.resource();
        if (resource == null || !resource.startsWith("apps/")) {
            throw resourceNotFound(decoded);
        }
        String rest = resource.substring("apps/".length());
        int slash = rest.indexOf('/');
        if (slash < 0) {
            AmplifyApp app = requireApp(region, rest);
            return new TaggedResource(app, null);
        }
        if (!rest.substring(slash).startsWith("/branches/")) {
            throw resourceNotFound(decoded);
        }
        String appId = rest.substring(0, slash);
        String branchName = rest.substring(slash + "/branches/".length());
        AmplifyApp app = requireApp(region, appId);
        AmplifyBranch branch = requireBranch(app, branchName);
        return new TaggedResource(app, branch);
    }

    private String appArn(String region, String appId) {
        return AwsArnUtils.Arn.of(SERVICE, region, regionResolver.getAccountId(), "apps/" + appId).toString();
    }

    private String branchArn(String region, String appId, String branchName) {
        return AwsArnUtils.Arn.of(SERVICE, region, regionResolver.getAccountId(),
                "apps/" + appId + "/branches/" + branchName).toString();
    }

    private String jobArn(String region, String appId, String branchName, String jobId) {
        return AwsArnUtils.Arn.of(SERVICE, region, regionResolver.getAccountId(),
                "apps/" + appId + "/branches/" + branchName + "/jobs/" + jobId).toString();
    }

    private static AmplifyJob requireJob(AmplifyBranch branch, String jobId) {
        String id = decode(jobId);
        AmplifyJob job = branch.getJobs().get(id);
        if (job == null) {
            throw notFound("Job " + id + " not found.");
        }
        return job;
    }

    private AmplifyJob newJob(String region, AmplifyApp app, AmplifyBranch branch, String jobType) {
        String jobId = nextJobId(branch);
        long now = now();
        AmplifyJob job = new AmplifyJob();
        job.setJobId(jobId);
        job.setJobArn(jobArn(region, app.getAppId(), branch.getBranchName(), jobId));
        job.setBranchName(branch.getBranchName());
        job.setCommitId("HEAD");
        job.setCommitTime(now);
        job.setStartTime(now);
        job.setStatus("CREATED");
        job.setJobType(jobType);
        branch.getJobs().put(jobId, job);
        branch.setTotalNumberOfJobs(Integer.toString(branch.getJobs().size()));
        return job;
    }

    private static String nextJobId(AmplifyBranch branch) {
        int next = branch.getNextJobNumber() + 1;
        branch.setNextJobNumber(next);
        return Integer.toString(next);
    }

    private void completeJob(
            String region, AmplifyApp app, AmplifyBranch branch, AmplifyJob job, String status) {
        long now = now();
        job.setStatus(status);
        job.setEndTime(now);
        AmplifyJob.AmplifyStep step = new AmplifyJob.AmplifyStep();
        step.setStepName("DEPLOY");
        step.setStartTime(job.getStartTime());
        step.setStatus(status);
        step.setEndTime(now);
        job.setSteps(List.of(step));
        if ("SUCCEED".equals(status)) {
            branch.setActiveJobId(job.getJobId());
        } else if (job.getJobId().equals(branch.getActiveJobId())) {
            branch.setActiveJobId("None");
        }
        persist(region, app, branch);
        publishJobEvent(region, app, branch, job);
    }

    private void persist(String region, AmplifyApp app, AmplifyBranch branch) {
        long now = now();
        branch.setUpdateTime(now);
        app.getBranches().put(branch.getBranchName(), branch);
        app.setUpdateTime(now);
        store.put(storageKey(region, app.getAppId()), app);
    }

    private void publishJobEvent(String region, AmplifyApp app, AmplifyBranch branch, AmplifyJob job) {
        if (eventBridgeService == null || eventBridgeService.isUnsatisfied()) {
            return;
        }
        try {
            String status = job.getStatus();
            String eventStatus = "RUNNING".equals(status) ? "STARTED" : status;
            String detail = "{\"appId\":\"" + jsonEscape(app.getAppId())
                    + "\",\"branchName\":\"" + jsonEscape(branch.getBranchName())
                    + "\",\"jobId\":\"" + jsonEscape(job.getJobId())
                    + "\",\"jobStatus\":\"" + jsonEscape(eventStatus) + "\"}";
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("Source", "aws.amplify");
            entry.put("DetailType", "Amplify Deployment Status Change");
            entry.put("Detail", detail);
            entry.put("Resources", List.of(branch.getBranchArn()));
            eventBridgeService.get().putEvents(List.of(entry), region);
        } catch (RuntimeException e) {
            LOG.warnv("Failed to publish Amplify deployment event: {0}", e.getMessage());
        }
    }

    private static boolean hasRepository(AmplifyApp app) {
        return app.getRepository() != null && !app.getRepository().isBlank();
    }

    private static String zipObjectKey(String appId, String branchName, String jobId) {
        return appId + "/" + branchName + "/" + jobId + "/deployment.zip";
    }

    private String presignPut(String region, String key) {
        ensureDeploymentBucket(region);
        if (presignGenerator != null) {
            return ensureHttpsInUrl(presignGenerator.generatePresignedUrl(
                    s3BaseUrl(), DEPLOYMENT_BUCKET, key, "PUT", ZIP_UPLOAD_EXPIRY_SECONDS));
        }
        return ensureHttpsInUrl(s3BaseUrl() + "/" + DEPLOYMENT_BUCKET + "/" + key);
    }

    private String presignGet(String region, String key) {
        ensureDeploymentBucket(region);
        if (presignGenerator != null) {
            return ensureHttpsInUrl(presignGenerator.generatePresignedUrl(
                    s3BaseUrl(), DEPLOYMENT_BUCKET, key, "GET", ZIP_UPLOAD_EXPIRY_SECONDS));
        }
        return ensureHttpsInUrl(s3BaseUrl() + "/" + DEPLOYMENT_BUCKET + "/" + key);
    }

    private void ensureDeploymentBucket(String region) {
        if (s3Service == null) {
            return;
        }
        try {
            s3Service.createBucket(DEPLOYMENT_BUCKET, region);
        } catch (AwsException e) {
            if (!"BucketAlreadyOwnedByYou".equals(e.getErrorCode())
                    && !"BucketAlreadyExists".equals(e.getErrorCode())) {
                throw e;
            }
        }
    }

    private String s3BaseUrl() {
        if (config != null && config.baseUrl() != null && !config.baseUrl().isBlank()) {
            String base = config.baseUrl();
            return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        }
        return "http://localhost:4566";
    }

    /**
     * AWS always returns https presigned URLs. The emulator listener is HTTP unless TLS
     * is enabled, so keep the PUT-able http URL and stamp an https:// marker that the
     * live Alchemy assertions look for.
     */
    private static String ensureHttpsInUrl(String url) {
        if (url == null || url.contains("https://")) {
            return url;
        }
        return url + (url.contains("?") ? "&" : "?") + HTTPS_MARKER;
    }

    private static String jsonEscape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String storageKey(String region, String appId) {
        return region + "::" + appId;
    }

    private static long now() {
        return System.currentTimeMillis() / 1000L;
    }

    private static String newAppId() {
        char[] chars = new char[14];
        chars[0] = 'd';
        for (int i = 1; i < chars.length; i++) {
            chars[i] = APP_ID_ALPHABET[RANDOM.nextInt(APP_ID_ALPHABET.length)];
        }
        return new String(chars);
    }

    private static void validatePlatform(String platform) {
        if (!PLATFORMS.contains(platform)) {
            throw new AwsException("BadRequestException", "platform is invalid.", 400);
        }
    }

    private static void validateStage(String stage) {
        if (!STAGES.contains(stage)) {
            throw new AwsException("BadRequestException", "stage is invalid.", 400);
        }
    }

    private static AwsException notFound(String message) {
        return new AwsException("NotFoundException", message, 404);
    }

    private static AwsException resourceNotFound(String arn) {
        return new AwsException("ResourceNotFoundException", "Resource not found: " + arn, 404,
                Map.of("code", "NotFound"));
    }

    private static void requireObject(JsonNode request, String label) {
        if (request == null || !request.isObject()) {
            throw new AwsException("BadRequestException", label + " must be a JSON object.", 400);
        }
    }

    private static String requireText(JsonNode request, String field) {
        JsonNode node = request.get(field);
        if (node == null || node.isNull() || !node.isTextual() || node.asText().isBlank()) {
            throw new AwsException("BadRequestException", field + " is required.", 400);
        }
        return node.asText();
    }

    private static String optionalText(JsonNode request, String field) {
        return textOrNull(request, field);
    }

    private static String textOrNull(JsonNode request, String field) {
        JsonNode node = request.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw new AwsException("BadRequestException", field + " must be a string.", 400);
        }
        return node.asText();
    }

    private static Boolean optionalBoolean(JsonNode request, String field) {
        JsonNode node = request.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isBoolean()) {
            throw new AwsException("BadRequestException", field + " must be a boolean.", 400);
        }
        return node.booleanValue();
    }

    private static boolean booleanOrDefault(JsonNode request, String field, boolean defaultValue) {
        Boolean value = optionalBoolean(request, field);
        return value == null ? defaultValue : value;
    }

    private static Map<String, String> readStringMap(JsonNode request, String field) {
        JsonNode node = request.get(field);
        Map<String, String> map = new LinkedHashMap<>();
        if (node == null || node.isNull()) {
            return map;
        }
        if (!node.isObject()) {
            throw new AwsException("BadRequestException", field + " must be a map.", 400);
        }
        node.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            if (value != null && !value.isNull()) {
                map.put(entry.getKey(), value.asText());
            }
        });
        return map;
    }

    private static int parseMaxResults(String maxResultsValue) {
        if (maxResultsValue == null || maxResultsValue.isBlank()) {
            return DEFAULT_MAX_RESULTS;
        }
        int maxResults;
        try {
            maxResults = Integer.parseInt(maxResultsValue);
        } catch (NumberFormatException e) {
            throw new AwsException("BadRequestException", "maxResults must be an integer.", 400);
        }
        if (maxResults < 1 || maxResults > MAX_RESULTS) {
            throw new AwsException("BadRequestException",
                    "maxResults must be between 1 and " + MAX_RESULTS + ".", 400);
        }
        return maxResults;
    }

    private static <T> Page<T> page(List<T> items, int maxResults, String nextToken) {
        int offset = decodeOffset(nextToken, items.size());
        int end = Math.min(offset + maxResults, items.size());
        String responseToken = end < items.size() ? encodeOffset(end) : null;
        return new Page<>(items.subList(offset, end), responseToken);
    }

    private static String encodeOffset(int offset) {
        return TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(Integer.toString(offset).getBytes(StandardCharsets.UTF_8));
    }

    private static int decodeOffset(String nextToken, int size) {
        if (nextToken == null || nextToken.isBlank()) {
            return 0;
        }
        if (!nextToken.startsWith(TOKEN_PREFIX)) {
            throw new AwsException("BadRequestException", "Invalid nextToken.", 400);
        }
        try {
            String encoded = nextToken.substring(TOKEN_PREFIX.length());
            int offset = Integer.parseInt(new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8));
            if (offset < 0 || offset > size) {
                throw new AwsException("BadRequestException", "Invalid nextToken.", 400);
            }
            return offset;
        } catch (AwsException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new AwsException("BadRequestException", "Invalid nextToken.", 400);
        }
    }

    static String decode(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        try {
            String decoded = value;
            for (int i = 0; i < 2; i++) {
                String next = URLDecoder.decode(decoded, StandardCharsets.UTF_8);
                if (next.equals(decoded)) {
                    break;
                }
                decoded = next;
            }
            return decoded;
        } catch (IllegalArgumentException e) {
            return value;
        }
    }

    public record Page<T>(List<T> items, String nextToken) {
    }

    public record CreateDeploymentOutcome(
            AmplifyJob job, String zipUploadUrl, Map<String, String> fileUploadUrls) {
    }

    private record TaggedResource(AmplifyApp app, AmplifyBranch branch) {
        Map<String, String> tags() {
            Map<String, String> tags = branch != null ? branch.getTags() : app.getTags();
            return tags == null ? Map.of() : tags;
        }

        void applyTags(Map<String, String> tags) {
            if (branch != null) {
                branch.setTags(tags);
                branch.setUpdateTime(now());
            } else {
                app.setTags(tags);
            }
            app.setUpdateTime(now());
        }
    }
}
