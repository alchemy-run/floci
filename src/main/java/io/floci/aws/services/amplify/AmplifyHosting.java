package io.floci.aws.services.amplify;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.eventbridge.EventBridgeService;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.s3.model.S3Object;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Amplify Hosting's jobs and deployments, and the sites they publish. A manual deployment stages a
 * job with a pre-signed upload URL (one zip, or one URL per file), and starting it deploys the
 * upload (or a zip or prefix in S3, or a zip at a URL): the job runs for a moment, then the branch
 * serves the files at {@code https://<branch>.<appId>.amplifyapp.com} and its custom domains, with
 * the app's rewrite and redirect rules and basic authorization. Each request is written to the
 * app's access logs. Job status changes are announced on EventBridge as
 * {@code Amplify Deployment Status Change} events.
 */
@ApplicationScoped
public class AmplifyHosting implements Resettable {
    private static final Logger LOG = Logger.getLogger(AmplifyHosting.class);
    static final long DEPLOY_MILLIS = 1_000;
    private static final int MAX_LOG_LINES = 10_000;
    private static final Pattern REGEX_RULE = Pattern.compile("^</(.*)/>$");
    private static final DateTimeFormatter LOG_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final Map<String, String> CONTENT_TYPES = Map.ofEntries(
            Map.entry("html", "text/html; charset=utf-8"), Map.entry("htm", "text/html; charset=utf-8"),
            Map.entry("css", "text/css; charset=utf-8"), Map.entry("js", "application/javascript"),
            Map.entry("mjs", "application/javascript"), Map.entry("json", "application/json"),
            Map.entry("txt", "text/plain; charset=utf-8"), Map.entry("xml", "application/xml"),
            Map.entry("svg", "image/svg+xml"), Map.entry("png", "image/png"), Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"), Map.entry("gif", "image/gif"), Map.entry("webp", "image/webp"),
            Map.entry("ico", "image/x-icon"), Map.entry("woff", "font/woff"), Map.entry("woff2", "font/woff2"),
            Map.entry("map", "application/json"), Map.entry("wasm", "application/wasm"),
            Map.entry("pdf", "application/pdf"));

    private final AccountAwareStorageBackend<ObjectNode> jobs;
    private final AccountAwareStorageBackend<ObjectNode> sites;
    private final Map<String, Deque<String>> accessLogs = new ConcurrentHashMap<>();
    private final Map<String, String> logLinks = new ConcurrentHashMap<>();
    private final AmplifyService amplify;
    private final Instance<S3Service> s3;
    private final Instance<EventBridgeService> eventBridge;
    private final RegionResolver regionResolver;
    private final EmulatorConfig config;
    private final ObjectMapper mapper;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "amplify-deployments");
        thread.setDaemon(true);
        return thread;
    });

    @Inject
    public AmplifyHosting(StorageFactory storageFactory, AmplifyService amplify, Instance<S3Service> s3,
                          Instance<EventBridgeService> eventBridge, RegionResolver regionResolver,
                          EmulatorConfig config, ObjectMapper mapper) {
        TypeReference<Map<String, ObjectNode>> type = new TypeReference<>() {};
        this.jobs = storageFactory.create("amplify", "amplify-jobs.json", type);
        this.sites = storageFactory.create("amplify", "amplify-sites.json", type);
        this.amplify = amplify;
        this.s3 = s3;
        this.eventBridge = eventBridge;
        this.regionResolver = regionResolver;
        this.config = config;
        this.mapper = mapper;
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }

    // ── Deployments ────────────────────────────────────────────────────────

    public synchronized ObjectNode createDeployment(String appId, String branchName, JsonNode in, String region) {
        AmplifyService.check("CreateDeployment", in, "appId", appId, "branchName", branchName);
        ObjectNode app = amplify.requireApp(appId, region);
        if (!app.path("repository").asText("").isEmpty()) {
            throw AmplifyService.badRequest("Manual deployments are for apps that aren't connected to a "
                    + "repository.");
        }
        amplify.requireBranch(appId, branchName, region);
        ObjectNode job = newJob(appId, branchName, "MANUAL", region);
        job.put("status", "PENDING");
        String token = UUID.randomUUID().toString().replace("-", "");
        job.put("uploadToken", token);
        ObjectNode out = mapper.createObjectNode();
        out.put("jobId", job.path("jobId").asText());
        ObjectNode fileUrls = out.putObject("fileUploadUrls");
        if (in.path("fileMap").isObject() && !in.get("fileMap").isEmpty()) {
            job.set("fileMap", in.get("fileMap").deepCopy());
            in.get("fileMap").fieldNames().forEachRemaining(path -> fileUrls.put(path, uploadUrl(token)
                    + "/" + path.replaceAll("^/+", "")));
            out.put("zipUploadUrl", "");
        } else {
            out.put("zipUploadUrl", uploadUrl(token));
        }
        saveJob(region, appId, branchName, job);
        return out;
    }

    private String uploadUrl(String token) {
        return config.effectiveBaseUrl() + "/_amplify/uploads/" + token;
    }

    /** Stores an upload a pre-signed URL names: a deployment's zip, or one file of its file map. */
    public synchronized void upload(String token, String path, byte[] body) {
        for (AccountAwareStorageBackend.AccountEntry<ObjectNode> entry : jobs.scanAllAccountEntries(k -> true)) {
            ObjectNode job = entry.value();
            if (!token.equals(job.path("uploadToken").asText())) {
                continue;
            }
            if (!"PENDING".equals(job.path("status").asText())) {
                throw new AwsException("AccessDenied", "The upload URL has expired.", 403);
            }
            String encoded = Base64.getEncoder().encodeToString(body);
            if (path == null || path.isEmpty()) {
                job.put("uploadedZip", encoded);
            } else {
                job.withObject("uploadedFiles").put(path, encoded);
            }
            jobs.putForAccount(entry.accountId(), entry.key(), job);
            return;
        }
        throw new AwsException("NoSuchUpload", "The upload URL is not valid.", 404);
    }

    public synchronized ObjectNode startDeployment(String appId, String branchName, JsonNode in, String region) {
        AmplifyService.check("StartDeployment", in, "appId", appId, "branchName", branchName);
        amplify.requireApp(appId, region);
        amplify.requireBranch(appId, branchName, region);
        ObjectNode job;
        if (in.hasNonNull("jobId")) {
            job = requireJob(appId, branchName, in.get("jobId").asText(), region);
            if (!"PENDING".equals(job.path("status").asText())) {
                throw AmplifyService.badRequest("Job " + in.get("jobId").asText() + " has already started.");
            }
        } else if (in.hasNonNull("sourceUrl")) {
            job = newJob(appId, branchName, "MANUAL", region);
            job.put("sourceUrl", in.get("sourceUrl").asText());
            job.put("sourceUrlType", in.path("sourceUrlType").asText("ZIP"));
        } else {
            throw AmplifyService.badRequest("StartDeployment needs the jobId of a created deployment or a sourceUrl.");
        }
        if (job.has("sourceUrl")) {
            job.put("sourceUrlType", job.path("sourceUrlType").asText("ZIP"));
        }
        return wrapSummary(run(region, appId, branchName, job, "DEPLOY"));
    }

    /** Starts a job: it runs for a moment on the deployment thread, then settles. */
    private ObjectNode run(String region, String appId, String branchName, ObjectNode job, String step) {
        long now = Instant.now().getEpochSecond();
        job.put("status", "RUNNING");
        job.put("startTime", now);
        ArrayNode steps = job.putArray("steps");
        steps.addObject().put("stepName", step).put("startTime", now).put("status", "RUNNING").put("endTime", now);
        saveJob(region, appId, branchName, job);
        publish(appId, branchName, job.path("jobId").asText(), "STARTED", region);
        String account = regionResolver.getAccountId();
        String jobId = job.path("jobId").asText();
        scheduler.schedule(() -> RequestScopes.runAs(account, region, () -> {
            try {
                settle(region, appId, branchName, jobId);
            } catch (RuntimeException e) {
                LOG.warnv(e, "Amplify job {0} of {1}/{2} could not settle", jobId, appId, branchName);
            }
        }), DEPLOY_MILLIS, TimeUnit.MILLISECONDS);
        return job;
    }

    private synchronized void settle(String region, String appId, String branchName, String jobId) {
        Optional<ObjectNode> found = jobs.get(jobKey(region, appId, branchName, jobId));
        if (found.isEmpty() || !"RUNNING".equals(found.get().path("status").asText())) {
            return;
        }
        ObjectNode job = found.get();
        String failure = null;
        Map<String, byte[]> files = Map.of();
        if (job.has("failure")) {
            failure = job.path("failure").asText();
        } else {
            try {
                files = content(job);
                if (files.isEmpty()) {
                    failure = "The deployment has no files: upload a zip to its zipUploadUrl, or files to its "
                            + "fileUploadUrls, before StartDeployment.";
                }
            } catch (AwsException e) {
                failure = e.getMessage();
            }
        }
        long now = Instant.now().getEpochSecond();
        job.put("endTime", now);
        ObjectNode step = (ObjectNode) job.withArray("steps").get(0);
        step.put("endTime", now);
        if (failure != null) {
            job.put("status", "FAILED");
            step.put("status", "FAILED");
            step.put("statusReason", failure);
        } else {
            ObjectNode site = mapper.createObjectNode();
            ObjectNode stored = site.putObject("files");
            files.forEach((path, bytes) -> stored.put(path, Base64.getEncoder().encodeToString(bytes)));
            sites.put(jobKey(region, appId, branchName, jobId), site);
            job.put("status", "SUCCEED");
            step.put("status", "SUCCEED");
            ObjectNode branch = amplify.requireBranch(appId, branchName, region);
            branch.put("activeJobId", jobId);
            branch.put("lastDeployTime", now);
            amplify.saveBranch(appId, branchName, region, branch);
        }
        job.remove(List.of("uploadedZip", "uploadedFiles"));
        saveJob(region, appId, branchName, job);
        publish(appId, branchName, jobId, job.path("status").asText(), region);
    }

    /** A deployment's files: its upload, or what its source URL names. */
    private Map<String, byte[]> content(ObjectNode job) {
        if (job.has("uploadedFiles")) {
            Map<String, byte[]> files = new LinkedHashMap<>();
            job.get("uploadedFiles").fields().forEachRemaining(e -> files.put(normalize(e.getKey()),
                    Base64.getDecoder().decode(e.getValue().asText())));
            return files;
        }
        if (job.has("uploadedZip")) {
            return unzip(Base64.getDecoder().decode(job.path("uploadedZip").asText()));
        }
        if (!job.has("sourceUrl")) {
            return Map.of();
        }
        String source = job.path("sourceUrl").asText();
        boolean prefix = "BUCKET_PREFIX".equals(job.path("sourceUrlType").asText());
        if (source.startsWith("s3://")) {
            String rest = source.substring("s3://".length());
            String bucket = rest.contains("/") ? rest.substring(0, rest.indexOf('/')) : rest;
            String key = rest.contains("/") ? rest.substring(rest.indexOf('/') + 1) : "";
            if (!s3.isResolvable()) {
                throw AmplifyService.badRequest("S3 is not available.");
            }
            if (prefix) {
                Map<String, byte[]> files = new LinkedHashMap<>();
                for (S3Object object : s3.get().listObjects(bucket, key, null, 100_000)) {
                    if (!object.getKey().endsWith("/")) {
                        files.put(normalize(object.getKey().substring(key.length())),
                                s3.get().getObject(bucket, object.getKey()).getData());
                    }
                }
                return files;
            }
            return unzip(s3.get().getObject(bucket, key).getData());
        }
        if (source.startsWith("https://") || source.startsWith("http://")) {
            try {
                HttpResponse<byte[]> response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                        .followRedirects(HttpClient.Redirect.NORMAL).build()
                        .send(HttpRequest.newBuilder(URI.create(source)).timeout(Duration.ofSeconds(30)).build(),
                                HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() / 100 != 2) {
                    throw AmplifyService.badRequest("The source URL answered " + response.statusCode() + ".");
                }
                return unzip(response.body());
            } catch (IOException e) {
                throw AmplifyService.badRequest("The source URL could not be read: " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw AmplifyService.badRequest("The source URL could not be read.");
            }
        }
        throw AmplifyService.badRequest("sourceUrl must be an s3:// or https:// URL.");
    }

    private static Map<String, byte[]> unzip(byte[] zip) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (!entry.isDirectory() && !entry.getName().contains("..")) {
                    files.put(normalize(entry.getName()), in.readAllBytes());
                }
            }
        } catch (IOException e) {
            throw AmplifyService.badRequest("The deployment is not a valid zip file.");
        }
        return files;
    }

    private static String normalize(String path) {
        return "/" + path.replace('\\', '/').replaceAll("^/+", "");
    }

    // ── Jobs ───────────────────────────────────────────────────────────────

    public synchronized ObjectNode startJob(String appId, String branchName, JsonNode in, String region) {
        AmplifyService.check("StartJob", in, "appId", appId, "branchName", branchName);
        ObjectNode app = amplify.requireApp(appId, region);
        amplify.requireBranch(appId, branchName, region);
        String type = in.path("jobType").asText();
        if ("RETRY".equals(type)) {
            String previousId = in.path("jobId").asText("");
            if (previousId.isEmpty()) {
                throw AmplifyService.badRequest("A RETRY job needs the jobId of the job to retry.");
            }
            ObjectNode previous = requireJob(appId, branchName, previousId, region);
            ObjectNode job = newJob(appId, branchName, "RETRY", region);
            Optional<ObjectNode> site = sites.get(jobKey(region, appId, branchName, previousId));
            if (site.isPresent()) {
                job.set("uploadedFiles", site.get().get("files").deepCopy());
            } else if (previous.has("sourceUrl")) {
                job.put("sourceUrl", previous.path("sourceUrl").asText());
                job.put("sourceUrlType", previous.path("sourceUrlType").asText("ZIP"));
            } else {
                job.put("failure", "Job " + previousId + " has nothing to deploy again.");
            }
            return wrapSummary(run(region, appId, branchName, job, "DEPLOY"));
        }
        if (app.path("repository").asText("").isEmpty()) {
            throw AmplifyService.badRequest("Branch " + branchName + " of app " + appId + " isn't connected to a "
                    + "repository; deploy it with CreateDeployment and StartDeployment.");
        }
        if (!"RELEASE".equals(type) && !"WEB_HOOK".equals(type)) {
            throw AmplifyService.badRequest("jobType must be RELEASE, RETRY or WEB_HOOK for a connected branch.");
        }
        ObjectNode job = newJob(appId, branchName, type, region);
        if (in.hasNonNull("commitId")) {
            job.put("commitId", in.get("commitId").asText());
        }
        if (in.hasNonNull("commitMessage")) {
            job.put("commitMessage", in.get("commitMessage").asText());
        }
        job.put("failure", "Floci doesn't clone repositories or run builds; deploy built files with "
                + "CreateDeployment and StartDeployment.");
        return wrapSummary(run(region, appId, branchName, job, "BUILD"));
    }

    public ObjectNode getJob(String appId, String branchName, String jobId, String region) {
        AmplifyService.check("GetJob", null, "appId", appId, "branchName", branchName, "jobId", jobId);
        amplify.requireApp(appId, region);
        amplify.requireBranch(appId, branchName, region);
        ObjectNode job = requireJob(appId, branchName, jobId, region);
        ObjectNode out = mapper.createObjectNode();
        ObjectNode body = out.putObject("job");
        body.set("summary", summary(job));
        body.set("steps", job.has("steps") ? job.get("steps").deepCopy() : mapper.createArrayNode());
        return out;
    }

    public ObjectNode listJobs(String appId, String branchName, Integer maxResults, String nextToken,
                               String region) {
        AmplifyService.check("ListJobs", null, "appId", appId, "branchName", branchName);
        amplify.requireApp(appId, region);
        amplify.requireBranch(appId, branchName, region);
        String prefix = AmplifyService.branchKey(region, appId, branchName) + "::";
        List<ObjectNode> all = jobs.scan(k -> k.startsWith(prefix)).stream()
                .sorted(Comparator.comparingLong((ObjectNode j) -> j.path("jobId").asLong()).reversed())
                .map(this::summary).toList();
        ObjectNode out = mapper.createObjectNode();
        ArrayNode list = out.putArray("jobSummaries");
        AmplifyService.page(all, maxResults, nextToken, out).forEach(list::add);
        return out;
    }

    public synchronized ObjectNode stopJob(String appId, String branchName, String jobId, String region) {
        AmplifyService.check("StopJob", null, "appId", appId, "branchName", branchName, "jobId", jobId);
        amplify.requireApp(appId, region);
        amplify.requireBranch(appId, branchName, region);
        ObjectNode job = requireJob(appId, branchName, jobId, region);
        String status = job.path("status").asText();
        if (!Set.of("PENDING", "PROVISIONING", "RUNNING").contains(status)) {
            throw AmplifyService.badRequest("Job " + jobId + " is " + status + " and can't be stopped.");
        }
        long now = Instant.now().getEpochSecond();
        job.put("status", "CANCELLED");
        job.put("endTime", now);
        job.withArray("steps").forEach(step -> ((ObjectNode) step).put("status", "CANCELLED").put("endTime", now));
        saveJob(region, appId, branchName, job);
        publish(appId, branchName, jobId, "CANCELLED", region);
        return wrapSummary(job);
    }

    public synchronized ObjectNode deleteJob(String appId, String branchName, String jobId, String region) {
        AmplifyService.check("DeleteJob", null, "appId", appId, "branchName", branchName, "jobId", jobId);
        amplify.requireApp(appId, region);
        ObjectNode branch = amplify.requireBranch(appId, branchName, region);
        ObjectNode job = requireJob(appId, branchName, jobId, region);
        if (jobId.equals(branch.path("activeJobId").asText())) {
            throw AmplifyService.badRequest("Job " + jobId + " is the branch's active job, which serves the branch; "
                    + "deploy another job before you delete it.");
        }
        if (Set.of("PENDING", "PROVISIONING", "RUNNING").contains(job.path("status").asText())) {
            throw AmplifyService.badRequest("Job " + jobId + " is still running; stop it first.");
        }
        jobs.delete(jobKey(region, appId, branchName, jobId));
        sites.delete(jobKey(region, appId, branchName, jobId));
        return wrapSummary(job);
    }

    public ObjectNode listArtifacts(String appId, String branchName, String jobId, String region) {
        AmplifyService.check("ListArtifacts", null, "appId", appId, "branchName", branchName, "jobId", jobId);
        amplify.requireApp(appId, region);
        amplify.requireBranch(appId, branchName, region);
        requireJob(appId, branchName, jobId, region);
        ObjectNode out = mapper.createObjectNode();
        out.putArray("artifacts");
        return out;
    }

    public ObjectNode getArtifactUrl(String artifactId) {
        AmplifyService.check("GetArtifactUrl", null, "artifactId", artifactId);
        throw new AwsException("NotFoundException", "Artifact " + artifactId + " not found.", 404);
    }

    /** Forgets a branch's jobs and sites, when the branch or its app is deleted. */
    synchronized void forgetBranch(String appId, String branchName, String region) {
        String prefix = AmplifyService.branchKey(region, appId, branchName) + "::";
        jobs.keys().stream().filter(k -> k.startsWith(prefix)).toList().forEach(jobs::delete);
        sites.keys().stream().filter(k -> k.startsWith(prefix)).toList().forEach(sites::delete);
    }

    private ObjectNode newJob(String appId, String branchName, String type, String region) {
        ObjectNode branch = amplify.requireBranch(appId, branchName, region);
        long next = branch.path("totalNumberOfJobs").asLong(0) + 1;
        branch.put("totalNumberOfJobs", Long.toString(next));
        amplify.saveBranch(appId, branchName, region, branch);
        long now = Instant.now().getEpochSecond();
        ObjectNode job = mapper.createObjectNode();
        String jobId = Long.toString(next);
        job.put("jobArn", branch.path("branchArn").asText() + "/jobs/" + String.format("%010d", next));
        job.put("jobId", jobId);
        job.put("commitId", "HEAD");
        job.put("commitMessage", "MANUAL".equals(type) ? "Manual deployment" : "");
        job.put("commitTime", now);
        job.put("startTime", now);
        job.put("status", "CREATED");
        job.put("jobType", type);
        return job;
    }

    private ObjectNode summary(ObjectNode job) {
        ObjectNode out = mapper.createObjectNode();
        for (String field : List.of("jobArn", "jobId", "commitId", "commitMessage", "commitTime", "startTime",
                "status", "endTime", "jobType", "sourceUrl", "sourceUrlType")) {
            if (job.has(field)) {
                out.set(field, job.get(field).deepCopy());
            }
        }
        return out;
    }

    private ObjectNode wrapSummary(ObjectNode job) {
        ObjectNode out = mapper.createObjectNode();
        out.set("jobSummary", summary(job));
        return out;
    }

    private ObjectNode requireJob(String appId, String branchName, String jobId, String region) {
        return jobs.get(jobKey(region, appId, branchName, jobId)).orElseThrow(() -> new AwsException(
                "NotFoundException", "Job " + jobId + " not found.", 404));
    }

    private void saveJob(String region, String appId, String branchName, ObjectNode job) {
        jobs.put(jobKey(region, appId, branchName, job.path("jobId").asText()), job);
    }

    private static String jobKey(String region, String appId, String branchName, String jobId) {
        return AmplifyService.branchKey(region, appId, branchName) + "::" + jobId;
    }

    private void publish(String appId, String branchName, String jobId, String status, String region) {
        if (!eventBridge.isResolvable()) {
            return;
        }
        ObjectNode detail = mapper.createObjectNode();
        detail.put("appId", appId);
        detail.put("branchName", branchName);
        detail.put("jobId", jobId);
        detail.put("jobStatus", status);
        eventBridge.get().publishServiceEvent("aws.amplify", "Amplify Deployment Status Change", detail.toString(),
                amplify.arn(region, "apps/" + appId + "/branches/" + branchName + "/jobs/" + jobId), region);
    }

    // ── Access logs ────────────────────────────────────────────────────────

    public ObjectNode generateAccessLogs(String appId, JsonNode in, String region) {
        AmplifyService.check("GenerateAccessLogs", in, "appId", appId);
        ObjectNode app = amplify.requireApp(appId, region);
        String domain = in.path("domainName").asText().toLowerCase(Locale.ROOT);
        boolean known = domain.equals(app.path("defaultDomain").asText())
                || amplify.listDomains(appId, null, null, region).path("domainAssociations").findValuesAsText(
                        "domainName").contains(domain);
        if (!known) {
            throw new AwsException("NotFoundException", "Domain " + domain + " isn't associated with app " + appId
                    + ".", 404);
        }
        long start = in.path("startTime").asLong(0);
        long end = in.hasNonNull("endTime") ? in.get("endTime").asLong() : Long.MAX_VALUE;
        StringBuilder csv = new StringBuilder("date,time,x-edge-location,sc-bytes,c-ip,cs-method,cs(Host),"
                + "cs-uri-stem,sc-status,cs(Referer),cs(User-Agent),cs-uri-query\n");
        for (String line : accessLogs.getOrDefault(logKey(regionResolver.getAccountId(), region, appId),
                new ConcurrentLinkedDeque<>())) {
            String[] fields = line.split("\t", 2);
            long at = Long.parseLong(fields[0]);
            String host = fields[1].split(",", 8)[6];
            if (at >= start && at <= end && (host.equals(domain) || host.endsWith("." + domain))) {
                csv.append(fields[1]).append('\n');
            }
        }
        String token = UUID.randomUUID().toString().replace("-", "");
        logLinks.put(token, csv.toString());
        return mapper.createObjectNode().put("logUrl", config.effectiveBaseUrl() + "/_amplify/logs/" + token
                + ".csv");
    }

    public String accessLog(String token) {
        String csv = logLinks.get(token.replaceAll("\\.csv$", ""));
        if (csv == null) {
            throw new AwsException("NoSuchKey", "The access log link is not valid.", 404);
        }
        return csv;
    }

    private static String logKey(String account, String region, String appId) {
        return account + "::" + region + "::" + appId;
    }

    // ── Serving ────────────────────────────────────────────────────────────

    /** What serving a site request produces. */
    public record Served(int status, byte[] body, String contentType, Map<String, String> headers) {}

    /** A site host's app and branch: the branch label and app id of a default domain, or a custom domain. */
    public record Site(String accountId, String region, String appId, String branchName) {}

    private static final Pattern DEFAULT_HOST = Pattern.compile("^([a-z0-9-]+)\\.(d[a-z0-9]+)\\.amplifyapp\\.com$");

    /** The site a host names, or null. */
    public Site siteFor(String host) {
        if (host == null) {
            return null;
        }
        String name = host.toLowerCase(Locale.ROOT).replaceAll(":\\d+$", "");
        Matcher matcher = DEFAULT_HOST.matcher(name);
        if (matcher.matches()) {
            String[] located = amplify.locateApp(matcher.group(2));
            if (located == null) {
                return null;
            }
            return amplify.branchByLabel(matcher.group(2), matcher.group(1), located[0], located[1])
                    .map(b -> new Site(located[0], located[1], matcher.group(2), b.path("branchName").asText()))
                    .orElse(null);
        }
        String[] custom = amplify.branchForCustomHost(name);
        return custom == null ? null : new Site(custom[0], custom[1], custom[2], custom[3]);
    }

    public Served serve(Site site, String method, String host, String path, String query, String authorization,
                        String clientIp, String referer, String userAgent) {
        Served served = RequestScopes.callAs(site.accountId(), site.region(), () -> {
            ObjectNode app = amplify.requireApp(site.appId(), site.region());
            ObjectNode branch = amplify.requireBranch(site.appId(), site.branchName(), site.region());
            String credentials = branch.path("enableBasicAuth").asBoolean(false)
                    ? branch.path("basicAuthCredentials").asText("")
                    : app.path("enableBasicAuth").asBoolean(false) ? app.path("basicAuthCredentials").asText("") : null;
            if (credentials != null && !("Basic " + credentials).equals(authorization)) {
                return new Served(401, "Unauthorized".getBytes(StandardCharsets.UTF_8), "text/plain",
                        Map.of("WWW-Authenticate", "Basic realm=\"Amplify\""));
            }
            String jobId = branch.path("activeJobId").asText("");
            Map<String, String> files = new LinkedHashMap<>();
            if (!jobId.isEmpty()) {
                sites.get(jobKey(site.region(), site.appId(), site.branchName(), jobId)).ifPresent(s ->
                        s.path("files").fields().forEachRemaining(e -> files.put(e.getKey(), e.getValue().asText())));
            }
            if (files.isEmpty()) {
                return new Served(404, "No deployment for this branch yet.".getBytes(StandardCharsets.UTF_8),
                        "text/plain", Map.of());
            }
            return applyRules(app.withArray("customRules"), files, path == null || path.isEmpty() ? "/" : path,
                    query);
        });
        log(site, method, host, path, query, served, clientIp, referer, userAgent);
        return served;
    }

    private Served applyRules(JsonNode rules, Map<String, String> files, String path, String query) {
        for (JsonNode rule : rules) {
            String source = rule.path("source").asText("");
            String target = rule.path("target").asText("");
            String status = rule.path("status").asText("200");
            String rewritten = matchRule(source, target, path);
            if (rewritten == null) {
                continue;
            }
            if ("200".equals(status) && files.containsKey(path) && !source.startsWith("</")) {
                // A rewrite never hides a file that exists, as Amplify's defaults do for SPAs.
                continue;
            }
            switch (status) {
                case "301", "302" -> {
                    return new Served(Integer.parseInt(status), new byte[0], "text/plain",
                            Map.of("Location", rewritten + (query == null || query.isEmpty() ? "" : "?" + query)));
                }
                case "404", "404-200" -> {
                    Served page = file(files, rewritten);
                    return new Served("404".equals(status) ? 404 : 200, page == null ? new byte[0] : page.body(),
                            page == null ? "text/plain" : page.contentType(), Map.of());
                }
                default -> {
                    if (rewritten.startsWith("http://") || rewritten.startsWith("https://")) {
                        return new Served(302, new byte[0], "text/plain", Map.of("Location", rewritten));
                    }
                    Served page = file(files, rewritten);
                    if (page != null) {
                        return page;
                    }
                }
            }
        }
        Served page = file(files, path);
        if (page != null) {
            return page;
        }
        Served notFound = file(files, "/404.html");
        return new Served(404, notFound == null ? "Not Found".getBytes(StandardCharsets.UTF_8) : notFound.body(),
                notFound == null ? "text/plain" : notFound.contentType(), Map.of());
    }

    /** The target a rule rewrites {@code path} to, or null when its source doesn't match. */
    static String matchRule(String source, String target, String path) {
        Matcher regex = REGEX_RULE.matcher(source);
        if (regex.matches()) {
            try {
                return Pattern.compile(regex.group(1)).matcher(path).matches() ? target : null;
            } catch (RuntimeException e) {
                return null;
            }
        }
        if (source.contains("<*>")) {
            String prefix = source.substring(0, source.indexOf("<*>"));
            String suffix = source.substring(source.indexOf("<*>") + 3);
            if (path.startsWith(prefix) && path.endsWith(suffix) && path.length() >= prefix.length()
                    + suffix.length()) {
                String captured = path.substring(prefix.length(), path.length() - suffix.length());
                return target.replace("<*>", captured);
            }
            return null;
        }
        if (source.contains("<") && source.contains(">")) {
            Pattern placeholder = Pattern.compile("<([A-Za-z0-9_]+)>");
            Matcher names = placeholder.matcher(source);
            List<String> captures = new ArrayList<>();
            StringBuilder regexSource = new StringBuilder();
            int last = 0;
            while (names.find()) {
                regexSource.append(Pattern.quote(source.substring(last, names.start()))).append("([^/]+)");
                captures.add(names.group(1));
                last = names.end();
            }
            regexSource.append(Pattern.quote(source.substring(last)));
            Matcher matched = Pattern.compile(regexSource.toString()).matcher(path);
            if (!matched.matches()) {
                return null;
            }
            String out = target;
            for (int i = 0; i < captures.size(); i++) {
                out = out.replace("<" + captures.get(i) + ">", matched.group(i + 1));
            }
            return out;
        }
        return source.equals(path) || source.equals(path + "/") || (source + "/").equals(path) ? target : null;
    }

    private static Served file(Map<String, String> files, String path) {
        String clean = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
        for (String candidate : List.of(clean, clean.endsWith("/") ? clean + "index.html" : clean + "/index.html",
                clean + ".html")) {
            String encoded = files.get(candidate);
            if (encoded != null) {
                String name = candidate.substring(candidate.lastIndexOf('/') + 1);
                String extension = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1)
                        .toLowerCase(Locale.ROOT) : "";
                return new Served(200, Base64.getDecoder().decode(encoded), CONTENT_TYPES.getOrDefault(extension,
                        "application/octet-stream"), Map.of());
            }
        }
        return null;
    }

    private void log(Site site, String method, String host, String path, String query, Served served,
                     String clientIp, String referer, String userAgent) {
        Instant now = Instant.now();
        String line = String.join(",", LOG_DATE.format(now), LOG_TIME.format(now), "FLOCI",
                Integer.toString(served.body().length), clientIp == null ? "-" : clientIp, method,
                host == null ? "-" : host.replaceAll(":\\d+$", ""), path == null ? "/" : path,
                Integer.toString(served.status()), referer == null ? "-" : referer.replace(",", "%2C"),
                userAgent == null ? "-" : userAgent.replace(",", "%2C"), query == null || query.isEmpty() ? "-"
                        : query.replace(",", "%2C"));
        Deque<String> lines = accessLogs.computeIfAbsent(logKey(site.accountId(), site.region(), site.appId()),
                k -> new ConcurrentLinkedDeque<>());
        lines.add(now.getEpochSecond() + "\t" + line);
        while (lines.size() > MAX_LOG_LINES) {
            lines.pollFirst();
        }
    }

    @Override
    public void clear() {
        jobs.clear();
        sites.clear();
        accessLogs.clear();
        logLinks.clear();
    }
}
