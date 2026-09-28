package io.github.hectorvent.floci.services.codebuild;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.StorageBackedMap;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.codebuild.model.Build;
import io.github.hectorvent.floci.services.codebuild.model.BuildPhase;
import io.github.hectorvent.floci.services.codebuild.model.Project;
import io.github.hectorvent.floci.services.codebuild.model.ProjectArtifacts;
import io.github.hectorvent.floci.services.codebuild.model.ProjectEnvironment;
import io.github.hectorvent.floci.services.codebuild.model.ProjectSource;
import io.github.hectorvent.floci.services.codebuild.model.ReportGroup;
import io.github.hectorvent.floci.services.codebuild.model.SourceCredential;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@ApplicationScoped
public class CodeBuildService {

    // key: region -> name -> project
    private Map<String, Map<String, Project>> projects = new ConcurrentHashMap<>();
    // key: region -> arn -> report group
    private Map<String, Map<String, ReportGroup>> reportGroups = new ConcurrentHashMap<>();
    // key: region -> arn -> source credential (token is stored but never returned)
    private Map<String, Map<String, SourceCredential>> sourceCredentials = new ConcurrentHashMap<>();
    // key: account:region -> buildId -> build (transient: builds are runtime state)
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Build>> builds = new ConcurrentHashMap<>();
    // key: account:region -> buildId -> request buildspec override (transient: builds are runtime state)
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, String>> buildspecOverrides = new ConcurrentHashMap<>();
    // key: account:region:projectName -> last allocated build number (durable across restarts)
    private Map<String, Long> persistedBuildCounters = new ConcurrentHashMap<>();
    // key: account:region:projectName -> build counter (runtime synchronization wrapper)
    private final ConcurrentHashMap<String, AtomicLong> buildCounters = new ConcurrentHashMap<>();

    private final CodeBuildRunner runner;
    private final EmulatorConfig config;
    private final StorageFactory storageFactory;
    private final ObjectMapper mapper;
    private Map<String, String> resourcePolicies = new ConcurrentHashMap<>();
    private Map<String, JsonNode> buildBatches = new ConcurrentHashMap<>();
    private Map<String, JsonNode> reports = new ConcurrentHashMap<>();

    @Inject
    public CodeBuildService(CodeBuildRunner runner, EmulatorConfig config, StorageFactory storageFactory,
                            ObjectMapper mapper) {
        this.runner = runner;
        this.config = config;
        this.storageFactory = storageFactory;
        this.mapper = mapper;
    }

    @PostConstruct
    void initializeStorage() {
        if (storageFactory == null) {
            return; // keeps non-CDI unit tests working
        }
        this.projects = storageBacked("codebuild-projects.json",
                new TypeReference<Map<String, Map<String, Project>>>() {});
        this.reportGroups = storageBacked("codebuild-report-groups.json",
                new TypeReference<Map<String, Map<String, ReportGroup>>>() {});
        this.sourceCredentials = storageBacked("codebuild-source-credentials.json",
                new TypeReference<Map<String, Map<String, SourceCredential>>>() {});
        this.persistedBuildCounters = storageBacked("codebuild-build-counters.json",
                new TypeReference<Map<String, Long>>() {});
        this.resourcePolicies = storageBacked("codebuild-resource-policies.json",
                new TypeReference<Map<String, String>>() {});
        this.buildBatches = storageBacked("codebuild-build-batches.json",
                new TypeReference<Map<String, JsonNode>>() {});
        this.reports = storageBacked("codebuild-reports.json",
                new TypeReference<Map<String, JsonNode>>() {});
        normalizeRegionMaps(projects);
        normalizeRegionMaps(reportGroups);
        normalizeRegionMaps(sourceCredentials);
    }

    private <V> Map<String, V> storageBacked(String fileName, TypeReference<Map<String, V>> typeReference) {
        return new StorageBackedMap<>(storageFactory.create("codebuild", fileName, typeReference));
    }

    /** After load, re-wrap the persisted inner maps as {@link ConcurrentHashMap} so per-region
     *  mutation stays thread-safe (Jackson deserializes them as plain LinkedHashMaps). */
    private <V> void normalizeRegionMaps(Map<String, Map<String, V>> resources) {
        for (Map.Entry<String, Map<String, V>> entry : new ArrayList<>(resources.entrySet())) {
            if (!(entry.getValue() instanceof ConcurrentHashMap)) {
                resources.put(entry.getKey(), new ConcurrentHashMap<>(entry.getValue()));
            }
        }
    }

    /** {@link StorageBackedMap} only flushes on a top-level put, so an in-place mutation of a
     *  region's inner map must be written back by re-putting the region entry. */
    private <V> void persistRegion(Map<String, Map<String, V>> resources, String region) {
        Map<String, V> regionResources = resources.get(region);
        if (regionResources != null) {
            resources.put(region, regionResources);
        }
    }

    private Map<String, Project> projectsFor(String region) {
        return projects.computeIfAbsent(region, r -> new ConcurrentHashMap<>());
    }

    private Map<String, ReportGroup> reportGroupsFor(String region) {
        return reportGroups.computeIfAbsent(region, r -> new ConcurrentHashMap<>());
    }

    private Map<String, SourceCredential> sourceCredentialsFor(String region) {
        return sourceCredentials.computeIfAbsent(region, r -> new ConcurrentHashMap<>());
    }

    private Map<String, Build> buildsFor(String account, String region) {
        return builds.computeIfAbsent(account + ":" + region, ignored -> new ConcurrentHashMap<>());
    }

    private Map<String, String> buildspecOverridesFor(String account, String region) {
        return buildspecOverrides.computeIfAbsent(account + ":" + region, ignored -> new ConcurrentHashMap<>());
    }

    // ---- Projects ----

    public Project createProject(String region, String account,
                                 String name, String description,
                                 ProjectSource source, List<ProjectSource> secondarySources,
                                 String sourceVersion,
                                 ProjectArtifacts artifacts, List<ProjectArtifacts> secondaryArtifacts,
                                 ProjectEnvironment environment,
                                 String serviceRole,
                                 Integer timeoutInMinutes, Integer queuedTimeoutInMinutes,
                                 String encryptionKey,
                                 List<Map<String, String>> tags,
                                 Map<String, Object> logsConfig,
                                 Map<String, Object> vpcConfig,
                                 Integer concurrentBuildLimit) {
        Map<String, Project> store = projectsFor(region);
        if (store.containsKey(name)) {
            throw new AwsException("ResourceAlreadyExistsException",
                    "Project already exists: " + name, 400);
        }
        validateProjectName(name);
        if (source == null || source.getType() == null) {
            throw new AwsException("InvalidInputException", "source.type is required", 400);
        }
        if (environment == null) {
            throw new AwsException("InvalidInputException", "environment is required", 400);
        }
        if (serviceRole == null || serviceRole.isBlank()) {
            throw new AwsException("InvalidInputException", "serviceRole is required", 400);
        }
        if (artifacts == null || artifacts.getType() == null) {
            throw new AwsException("InvalidInputException", "artifacts.type is required", 400);
        }

        double now = Instant.now().toEpochMilli() / 1000.0;
        Project project = new Project();
        project.setName(name);
        project.setArn(AwsArnUtils.Arn.of("codebuild", region, account, "project/" + name).toString());
        project.setDescription(description);
        project.setSource(source);
        project.setSecondarySources(secondarySources);
        project.setSourceVersion(sourceVersion);
        project.setArtifacts(artifacts);
        project.setSecondaryArtifacts(secondaryArtifacts);
        project.setEnvironment(environment);
        project.setServiceRole(serviceRole);
        project.setTimeoutInMinutes(timeoutInMinutes != null ? timeoutInMinutes : 60);
        project.setQueuedTimeoutInMinutes(queuedTimeoutInMinutes != null ? queuedTimeoutInMinutes : 480);
        project.setEncryptionKey(encryptionKey);
        project.setTags(tags);
        project.setCreated(now);
        project.setLastModified(now);
        project.setLogsConfig(logsConfig);
        project.setVpcConfig(vpcConfig);
        project.setConcurrentBuildLimit(concurrentBuildLimit);
        project.setProjectVisibility("PRIVATE");

        store.put(name, project);
        persistRegion(projects, region);
        return project;
    }

    public Project updateProject(String region, String name,
                                 String description,
                                 ProjectSource source, List<ProjectSource> secondarySources,
                                 String sourceVersion,
                                 ProjectArtifacts artifacts, List<ProjectArtifacts> secondaryArtifacts,
                                 ProjectEnvironment environment,
                                 String serviceRole,
                                 Integer timeoutInMinutes, Integer queuedTimeoutInMinutes,
                                 String encryptionKey,
                                 List<Map<String, String>> tags,
                                 Map<String, Object> logsConfig,
                                 Map<String, Object> vpcConfig,
                                 Integer concurrentBuildLimit) {
        Map<String, Project> store = projectsFor(region);
        Project project = store.get(name);
        if (project == null) {
            throw new AwsException("ResourceNotFoundException", "Project not found: " + name, 400);
        }

        if (description != null) { project.setDescription(description); }
        if (source != null) { project.setSource(source); }
        if (secondarySources != null) { project.setSecondarySources(secondarySources); }
        if (sourceVersion != null) { project.setSourceVersion(sourceVersion); }
        if (artifacts != null) { project.setArtifacts(artifacts); }
        if (secondaryArtifacts != null) { project.setSecondaryArtifacts(secondaryArtifacts); }
        if (environment != null) { project.setEnvironment(environment); }
        if (serviceRole != null) { project.setServiceRole(serviceRole); }
        if (timeoutInMinutes != null) { project.setTimeoutInMinutes(timeoutInMinutes); }
        if (queuedTimeoutInMinutes != null) { project.setQueuedTimeoutInMinutes(queuedTimeoutInMinutes); }
        if (encryptionKey != null) { project.setEncryptionKey(encryptionKey); }
        if (tags != null) { project.setTags(tags); }
        if (logsConfig != null) { project.setLogsConfig(logsConfig); }
        if (vpcConfig != null) { project.setVpcConfig(vpcConfig); }
        if (concurrentBuildLimit != null) { project.setConcurrentBuildLimit(concurrentBuildLimit); }
        project.setLastModified(Instant.now().toEpochMilli() / 1000.0);

        persistRegion(projects, region);
        return project;
    }

    public synchronized void deleteProject(String region, String name) {
        validateProjectName(name);
        Map<String, Project> store = projectsFor(region);
        Project project = store.get(name);
        if (project != null) {
            resourcePolicies.remove(project.getArn());
            store.remove(name);
            persistRegion(projects, region);
        }
    }

    public List<Project> batchGetProjects(String region, List<String> names) {
        Map<String, Project> store = projectsFor(region);
        return names.stream()
                .map(identifier -> {
                    Project project = store.get(identifier);
                    if (project != null) {
                        return project;
                    }
                    return store.values().stream()
                            .filter(candidate -> identifier.equals(candidate.getArn()))
                            .findFirst()
                            .orElse(null);
                })
                .filter(p -> p != null)
                .collect(Collectors.toList());
    }

    public List<String> listProjects(String region) {
        return new ArrayList<>(projectsFor(region).keySet());
    }

    // ---- Report Groups ----

    public ReportGroup createReportGroup(String region, String account,
                                         String name, String type,
                                         Map<String, Object> exportConfig,
                                         List<Map<String, String>> tags) {
        Map<String, ReportGroup> store = reportGroupsFor(region);
        String arn = AwsArnUtils.Arn.of("codebuild", region, account, "report-group/" + name).toString();
        if (store.containsKey(arn)) {
            throw new AwsException("ResourceAlreadyExistsException",
                    "Report group already exists: " + name, 400);
        }
        if (name == null || name.isBlank()) {
            throw new AwsException("InvalidInputException", "name is required", 400);
        }
        if (type == null) {
            throw new AwsException("InvalidInputException", "type is required", 400);
        }

        double now = Instant.now().toEpochMilli() / 1000.0;
        ReportGroup rg = new ReportGroup();
        rg.setArn(arn);
        rg.setName(name);
        rg.setType(type);
        rg.setExportConfig(exportConfig);
        rg.setCreated(now);
        rg.setLastModified(now);
        rg.setTags(tags);
        rg.setStatus("ACTIVE");

        store.put(arn, rg);
        persistRegion(reportGroups, region);
        return rg;
    }

    public ReportGroup updateReportGroup(String region, String arn,
                                          Map<String, Object> exportConfig,
                                          List<Map<String, String>> tags) {
        Map<String, ReportGroup> store = reportGroupsFor(region);
        ReportGroup rg = store.get(arn);
        if (rg == null) {
            throw new AwsException("ResourceNotFoundException", "Report group not found: " + arn, 400);
        }
        if (exportConfig != null) { rg.setExportConfig(exportConfig); }
        if (tags != null) { rg.setTags(tags); }
        rg.setLastModified(Instant.now().toEpochMilli() / 1000.0);
        persistRegion(reportGroups, region);
        return rg;
    }

    public void deleteReportGroup(String region, String arn) {
        deleteReportGroup(region, arn, false);
    }

    public synchronized void deleteReportGroup(String region, String arn, boolean deleteReports) {
        if (arn == null || arn.isBlank()) {
            throw new AwsException("InvalidInputException", "arn is required", 400);
        }
        Map<String, ReportGroup> store = reportGroupsFor(region);
        if (store.containsKey(arn)) {
            List<String> reportArns = reports.entrySet().stream()
                    .filter(entry -> arn.equals(entry.getValue().path("reportGroupArn").asText()))
                    .map(Map.Entry::getKey).toList();
            if (!deleteReports && !reportArns.isEmpty()) {
                throw new AwsException("InvalidInputException", "Report group contains reports", 400);
            }
            reportArns.forEach(reports::remove);
            resourcePolicies.remove(arn);
            store.remove(arn);
            persistRegion(reportGroups, region);
        }
    }

    public List<ReportGroup> batchGetReportGroups(String region, List<String> arns) {
        Map<String, ReportGroup> store = reportGroupsFor(region);
        return arns.stream()
                .map(store::get)
                .filter(rg -> rg != null)
                .collect(Collectors.toList());
    }

    public List<String> listReportGroups(String region) {
        return new ArrayList<>(reportGroupsFor(region).keySet());
    }

    // ---- Resource Policies ----

    public synchronized String getResourcePolicy(String region, String account, String resourceArn) {
        requirePolicyResource(region, account, resourceArn);
        return resourcePolicies.get(resourceArn);
    }

    public synchronized void putResourcePolicy(String region, String account, String resourceArn, String policy) {
        requirePolicyResource(region, account, resourceArn);
        validateResourcePolicy(policy);
        resourcePolicies.put(resourceArn, policy);
    }

    public synchronized void deleteResourcePolicy(String region, String account, String resourceArn) {
        validatePolicyResourceArn(region, account, resourceArn);
        resourcePolicies.remove(resourceArn);
    }

    private AwsArnUtils.Arn validatePolicyResourceArn(String region, String account, String resourceArn) {
        AwsArnUtils.Arn arn;
        try {
            arn = AwsArnUtils.parse(resourceArn);
        } catch (IllegalArgumentException e) {
            throw new AwsException("InvalidInputException", "Invalid resourceArn", 400);
        }
        boolean supportedResource = arn.resource().matches("(?:project|report-group)/[A-Za-z0-9][A-Za-z0-9_-]*");
        String expectedArn = AwsArnUtils.Arn.of("codebuild", region, account, arn.resource()).toString();
        if (!supportedResource || !expectedArn.equals(resourceArn)) {
            throw new AwsException("InvalidInputException",
                    "resourceArn must identify a CodeBuild project or report group owned by this account in this region", 400);
        }
        return arn;
    }

    private void requirePolicyResource(String region, String account, String resourceArn) {
        AwsArnUtils.Arn arn = validatePolicyResourceArn(region, account, resourceArn);
        boolean exists;
        if (arn.resource().startsWith("project/")) {
            Project project = projectsFor(region).get(arn.resource().substring("project/".length()));
            exists = project != null && resourceArn.equals(project.getArn());
        } else {
            exists = reportGroupsFor(region).containsKey(resourceArn);
        }
        if (!exists) {
            throw new AwsException("ResourceNotFoundException", "Resource not found: " + resourceArn, 400);
        }
    }

    private void validateResourcePolicy(String policy) {
        if (policy == null || policy.isBlank()) {
            throw new AwsException("InvalidInputException", "policy is required", 400);
        }
        JsonNode document;
        try {
            document = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(policy);
        } catch (JsonProcessingException e) {
            throw new AwsException("InvalidInputException", "policy must be a valid JSON policy document", 400);
        }
        if (document == null || !document.isObject()) {
            throw new AwsException("InvalidInputException", "policy must be a JSON object", 400);
        }
        JsonNode statements = document.path("Statement");
        if (statements.isObject()) {
            validatePolicyStatement(statements);
        } else if (statements.isArray() && !statements.isEmpty()) {
            for (JsonNode statement : statements) {
                validatePolicyStatement(statement);
            }
        } else {
            throw new AwsException("InvalidInputException", "policy must contain a Statement", 400);
        }
    }

    private void validatePolicyStatement(JsonNode statement) {
        String effect = statement.path("Effect").asText();
        if (!statement.isObject() || !("Allow".equals(effect) || "Deny".equals(effect))
                || !(statement.hasNonNull("Principal") || statement.hasNonNull("NotPrincipal"))
                || !(statement.hasNonNull("Action") || statement.hasNonNull("NotAction"))
                || !(statement.hasNonNull("Resource") || statement.hasNonNull("NotResource"))) {
            throw new AwsException("InvalidInputException", "Invalid resource policy statement", 400);
        }
    }

    // ---- Batch Builds and Reports ----

    void configureBuildBatch(String region, String name, Map<String, Object> batchConfig) {
        Project project = projectsFor(region).get(name);
        project.setBuildBatchConfig(batchConfig);
        persistRegion(projects, region);
    }

    void startBuildBatch(String region, String account, JsonNode request) {
        Project project = requireProject(region, account, request.path("projectName").asText(null));
        if (project.getBuildBatchConfig() == null && !request.hasNonNull("buildBatchConfigOverride")) {
            throw new AwsException("InvalidInputException", "Project has no build batch configuration", 400);
        }
        throw unsupportedExecution("Batch build execution");
    }

    private Project requireProject(String region, String account, String name) {
        if (name == null || name.isBlank()) {
            throw new AwsException("InvalidInputException", "projectName is required", 400);
        }
        Project project = projectsFor(region).get(name);
        String arn = AwsArnUtils.Arn.of("codebuild", region, account, "project/" + name).toString();
        if (project == null || !arn.equals(project.getArn())) {
            throw new AwsException("ResourceNotFoundException", "Project not found: " + name, 400);
        }
        return project;
    }

    static AwsException unsupportedExecution(String operation) {
        return new AwsException("InvalidInputException", operation + " is not supported by Floci", 400);
    }

    Map<String, Object> batchGetBuildBatches(String region, String account, List<String> ids) {
        return batchGetRecords(buildBatches, region, account, "build-batch", ids,
                "buildBatches", "buildBatchesNotFound");
    }

    Map<String, Object> listBuildBatchesForProject(String region, String account, JsonNode request) {
        Project project = requireProject(region, account, request.path("projectName").asText(null));
        List<JsonNode> records = scopedRecords(buildBatches, region, account, "build-batch").stream()
                .filter(batch -> project.getName().equals(batch.path("projectName").asText()))
                .toList();
        return listRecords(records, request, "startTime", "id", "ids");
    }

    void stopOrRetryBuildBatch(String region, String account, String id) {
        String arn = recordArn(region, account, "build-batch", id);
        if (!buildBatches.containsKey(arn)) {
            throw new AwsException("ResourceNotFoundException", "Build batch not found: " + id, 400);
        }
        throw unsupportedExecution("Batch build execution");
    }

    Map<String, Object> deleteBuildBatch(String region, String account, String id) {
        String arn = recordArn(region, account, "build-batch", id);
        JsonNode batch = buildBatches.get(arn);
        if (batch != null && !batch.path("complete").asBoolean()) {
            throw new AwsException("InvalidInputException", "Cannot delete an incomplete build batch", 400);
        }
        if (batch != null && !batch.path("buildGroups").isEmpty()) {
            throw unsupportedExecution("Deleting batch member builds");
        }
        buildBatches.remove(arn);
        return Map.of("statusCode", batch == null ? "RESOURCE_NOT_FOUND" : "SUCCEEDED",
                "buildsDeleted", List.of(), "buildsNotDeleted", List.of());
    }

    Map<String, Object> listReportsForReportGroup(String region, String account, JsonNode request) {
        String arn = request.path("reportGroupArn").asText(null);
        requireReportGroup(region, account, arn);
        List<JsonNode> records = scopedRecords(reports, region, account, "report").stream()
                .filter(report -> arn.equals(report.path("reportGroupArn").asText())).toList();
        return listRecords(records, request, "created", "arn", "reports");
    }

    Map<String, Object> batchGetReports(String region, String account, List<String> arns) {
        return batchGetRecords(reports, region, account, "report", arns, "reports", "reportsNotFound");
    }

    Map<String, Object> describeReport(String region, String account, JsonNode request, String field) {
        String arn = recordArn(region, account, "report", request.path("reportArn").asText(null));
        JsonNode report = reports.get(arn);
        if (report == null) {
            String error = "testCases".equals(field) ? "ResourceNotFoundException" : "InvalidInputException";
            throw new AwsException(error, "Report not found: " + arn, 400);
        }
        if (request.hasNonNull("filter") || request.hasNonNull("sortBy") || request.hasNonNull("sortOrder")) {
            throw unsupportedExecution("Filtered or sorted report detail queries");
        }
        List<JsonNode> details = new ArrayList<>();
        report.path(field).forEach(details::add);
        return page(details, request, field);
    }

    Map<String, Object> getReportGroupTrend(String region, String account, JsonNode request) {
        String arn = request.path("reportGroupArn").asText(null);
        requireReportGroup(region, account, arn);
        String field = request.path("trendField").asText();
        if (!List.of("DURATION", "PASS_RATE", "TOTAL", "LINE_COVERAGE", "LINES_COVERED", "LINES_MISSED",
                "BRANCH_COVERAGE", "BRANCHES_COVERED", "BRANCHES_MISSED").contains(field)) {
            throw new AwsException("InvalidInputException", "Invalid trendField", 400);
        }
        int count = request.path("numOfReports").asInt(10);
        if (count < 1 || count > 100) {
            throw new AwsException("InvalidInputException", "numOfReports must be between 1 and 100", 400);
        }
        List<JsonNode> selected = scopedRecords(reports, region, account, "report").stream()
                .filter(report -> arn.equals(report.path("reportGroupArn").asText()))
                .sorted(Comparator.comparingDouble((JsonNode report) -> report.path("created").asDouble()).reversed())
                .limit(count).toList();
        if (!selected.isEmpty()) {
            throw unsupportedExecution("Report trend aggregation");
        }
        return Map.of("rawData", List.of());
    }

    void deleteReport(String region, String account, String arn) {
        reports.remove(recordArn(region, account, "report", arn));
    }

    private void requireReportGroup(String region, String account, String arn) {
        String validated = recordArn(region, account, "report-group", arn);
        if (!reportGroupsFor(region).containsKey(validated)) {
            throw new AwsException("ResourceNotFoundException", "Report group not found: " + arn, 400);
        }
    }

    private String recordArn(String region, String account, String type, String id) {
        if (id == null || id.isBlank()) {
            throw new AwsException("InvalidInputException", "Resource identifier is required", 400);
        }
        String prefix = AwsArnUtils.Arn.of("codebuild", region, account, type + "/").toString();
        if (id.startsWith("arn:")) {
            if (!id.startsWith(prefix) || id.length() == prefix.length()) {
                throw new AwsException("InvalidInputException", "Invalid CodeBuild resource ARN: " + id, 400);
            }
            return id;
        }
        if (!"build-batch".equals(type) || !id.contains(":")) {
            throw new AwsException("InvalidInputException", "Invalid CodeBuild resource identifier: " + id, 400);
        }
        return prefix + id;
    }

    private List<JsonNode> scopedRecords(Map<String, JsonNode> store, String region, String account, String type) {
        String prefix = AwsArnUtils.Arn.of("codebuild", region, account, type + "/").toString();
        return store.entrySet().stream().filter(entry -> entry.getKey().startsWith(prefix))
                .map(Map.Entry::getValue).toList();
    }

    private Map<String, Object> batchGetRecords(Map<String, JsonNode> store, String region, String account,
                                               String type, List<String> ids, String foundKey, String missingKey) {
        if (ids == null || ids.isEmpty() || ids.size() > 100) {
            throw new AwsException("InvalidInputException", "Specify between 1 and 100 identifiers", 400);
        }
        List<JsonNode> found = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String id : ids) {
            JsonNode record = store.get(recordArn(region, account, type, id));
            if (record == null) {
                missing.add(id);
            } else {
                found.add(record);
            }
        }
        return Map.of(foundKey, found, missingKey, missing);
    }

    private Map<String, Object> listRecords(List<JsonNode> records, JsonNode request,
                                           String timestamp, String identifier, String responseKey) {
        String order = request.path("sortOrder").asText("DESCENDING");
        if (!List.of("ASCENDING", "DESCENDING").contains(order)) {
            throw new AwsException("InvalidInputException", "Invalid sortOrder", 400);
        }
        String status = request.path("filter").path("status").asText(null);
        Comparator<JsonNode> comparator = Comparator.comparingDouble((JsonNode record) -> record.path(timestamp).asDouble())
                .thenComparing(record -> record.path(identifier).asText());
        List<String> ids = records.stream()
                .filter(record -> status == null || status.equals(record.path("status").asText())
                        || status.equals(record.path("buildBatchStatus").asText()))
                .sorted("ASCENDING".equals(order) ? comparator : comparator.reversed())
                .map(record -> record.path(identifier).asText()).toList();
        return page(ids, request, responseKey);
    }

    private <T> Map<String, Object> page(List<T> records, JsonNode request, String field) {
        int maxResults = request.path("maxResults").asInt(100);
        int offset;
        try {
            offset = Integer.parseInt(request.path("nextToken").asText("0"));
        } catch (NumberFormatException e) {
            throw new AwsException("InvalidInputException", "Invalid nextToken", 400);
        }
        if (maxResults < 1 || maxResults > 100 || offset < 0 || offset > records.size()) {
            throw new AwsException("InvalidInputException", "Invalid pagination parameters", 400);
        }
        int end = (int) Math.min((long) offset + maxResults, records.size());
        Map<String, Object> response = new HashMap<>();
        response.put(field, records.subList(offset, end));
        if (end < records.size()) {
            response.put("nextToken", String.valueOf(end));
        }
        return response;
    }

    // ---- Source Credentials ----

    public SourceCredential importSourceCredentials(String region, String account,
                                                     String token, String serverType, String authType,
                                                     Boolean shouldOverwrite) {
        Map<String, SourceCredential> store = sourceCredentialsFor(region);
        // One credential per serverType+authType combo — overwrite existing by default
        String key = serverType + "/" + authType;
        SourceCredential existing = store.values().stream()
                .filter(c -> c.getServerType().equals(serverType) && c.getAuthType().equals(authType))
                .findFirst().orElse(null);
        if (existing != null && Boolean.FALSE.equals(shouldOverwrite)) {
            throw new AwsException("ResourceAlreadyExistsException",
                    "Source credentials already exist for " + serverType + "/" + authType, 400);
        }

        String arn = AwsArnUtils.Arn.of("codebuild", region, account, "token/" + serverType.toLowerCase() + "-" + UUID.randomUUID()).toString();
        if (existing != null) {
            arn = existing.getArn();
            store.remove(existing.getArn());
        }

        SourceCredential cred = new SourceCredential();
        cred.setArn(arn);
        cred.setServerType(serverType);
        cred.setAuthType(authType);
        // Token is accepted but not stored in plaintext in a returned field
        store.put(arn, cred);
        persistRegion(sourceCredentials, region);
        return cred;
    }

    public List<SourceCredential> listSourceCredentials(String region) {
        return new ArrayList<>(sourceCredentialsFor(region).values());
    }

    public void deleteSourceCredentials(String region, String arn) {
        Map<String, SourceCredential> store = sourceCredentialsFor(region);
        if (store.remove(arn) == null) {
            throw new AwsException("ResourceNotFoundException",
                    "Source credentials not found: " + arn, 400);
        }
        persistRegion(sourceCredentials, region);
    }

    // ---- Curated Environment Images ----

    public List<Map<String, Object>> listCuratedEnvironmentImages() {
        // Return the standard CodeBuild curated platform/language/image list
        return List.of(
                Map.of("platform", "AMAZON_LINUX_2",
                        "languages", List.of(
                                Map.of("language", "JAVA",
                                        "images", List.of(
                                                Map.of("name", "aws/codebuild/amazonlinux2-x86_64-standard:5.0",
                                                        "description", "AWS CodeBuild - amazonlinux2 - 5.0",
                                                        "versions", List.of("aws/codebuild/amazonlinux2-x86_64-standard:5.0")))),
                                Map.of("language", "PYTHON",
                                        "images", List.of(
                                                Map.of("name", "aws/codebuild/amazonlinux2-x86_64-standard:5.0",
                                                        "description", "AWS CodeBuild - amazonlinux2 - 5.0",
                                                        "versions", List.of("aws/codebuild/amazonlinux2-x86_64-standard:5.0")))),
                                Map.of("language", "NODE_JS",
                                        "images", List.of(
                                                Map.of("name", "aws/codebuild/amazonlinux2-x86_64-standard:5.0",
                                                        "description", "AWS CodeBuild - amazonlinux2 - 5.0",
                                                        "versions", List.of("aws/codebuild/amazonlinux2-x86_64-standard:5.0")))))),
                Map.of("platform", "UBUNTU",
                        "languages", List.of(
                                Map.of("language", "JAVA",
                                        "images", List.of(
                                                Map.of("name", "aws/codebuild/standard:7.0",
                                                        "description", "AWS CodeBuild - Ubuntu - 7.0",
                                                        "versions", List.of("aws/codebuild/standard:7.0")))),
                                Map.of("language", "PYTHON",
                                        "images", List.of(
                                                Map.of("name", "aws/codebuild/standard:7.0",
                                                        "description", "AWS CodeBuild - Ubuntu - 7.0",
                                                        "versions", List.of("aws/codebuild/standard:7.0")))),
                                Map.of("language", "NODE_JS",
                                        "images", List.of(
                                                Map.of("name", "aws/codebuild/standard:7.0",
                                                        "description", "AWS CodeBuild - Ubuntu - 7.0",
                                                        "versions", List.of("aws/codebuild/standard:7.0")))))));
    }

    private void validateProjectName(String name) {
        if (name == null || name.length() < 2 || name.length() > 150) {
            throw new AwsException("InvalidInputException",
                    "Project name must be between 2 and 150 characters", 400);
        }
    }

    // ---- Builds ----

    public Build startBuild(String region, String account, String projectName,
                            String buildspecOverride,
                            ProjectEnvironment environmentOverride,
                            ProjectArtifacts artifactsOverride,
                            String sourceVersion,
                            Integer timeoutOverride,
                            String imageOverride,
                            String computeTypeOverride) {
        Project project = projectsFor(region).get(projectName);
        if (project == null) {
            throw new AwsException("ResourceNotFoundException", "Project not found: " + projectName, 400);
        }

        String counterKey = account + ":" + region + ":" + projectName;
        AtomicLong counter = buildCounters.computeIfAbsent(counterKey,
                key -> new AtomicLong(persistedBuildCounters.getOrDefault(key, 0L)));
        long buildNumber;
        synchronized (counter) {
            buildNumber = counter.incrementAndGet();
            persistedBuildCounters.put(counterKey, buildNumber);
        }

        String buildId = projectName + ":" + buildNumber;
        String arn = AwsArnUtils.Arn.of("codebuild", region, account, "build/" + buildId).toString();

        Build build = new Build();
        build.setId(buildId);
        build.setArn(arn);
        build.setBuildNumber(buildNumber);
        build.setBuildStatus("IN_PROGRESS");
        build.setBuildComplete(false);
        build.setCurrentPhase("SUBMITTED");
        build.setProjectName(projectName);
        build.setInitiator("user");
        build.setStartTime(Instant.now().toEpochMilli() / 1000.0);
        build.setSource(project.getSource());
        build.setArtifacts(artifactsOverride != null ? artifactsOverride : project.getArtifacts());
        build.setTimeoutInMinutes(timeoutOverride != null ? timeoutOverride : project.getTimeoutInMinutes());
        build.setQueuedTimeoutInMinutes(project.getQueuedTimeoutInMinutes());
        build.setEncryptionKey(project.getEncryptionKey());

        ProjectEnvironment env = environmentOverride != null ? environmentOverride : project.getEnvironment();
        if (imageOverride != null || computeTypeOverride != null) {
            ProjectEnvironment merged = new ProjectEnvironment();
            merged.setType(env != null ? env.getType() : null);
            merged.setImage(imageOverride != null ? imageOverride : (env != null ? env.getImage() : null));
            merged.setComputeType(computeTypeOverride != null ? computeTypeOverride : (env != null ? env.getComputeType() : null));
            merged.setEnvironmentVariables(env != null ? env.getEnvironmentVariables() : null);
            merged.setPrivilegedMode(env != null ? env.getPrivilegedMode() : null);
            build.setEnvironment(merged);
        } else {
            build.setEnvironment(env);
        }

        build.setPhases(new CopyOnWriteArrayList<>());
        runner.validateExecution(build, project);

        buildsFor(account, region).put(buildId, build);
        if (buildspecOverride != null && !buildspecOverride.isBlank()) {
            buildspecOverridesFor(account, region).put(buildId, buildspecOverride);
        }
        Build responseBuild = copyBuild(build);

        runner.startBuild(region, build, project, buildspecOverride);

        return responseBuild;
    }

    public Build getBuild(String region, String account, String buildId) {
        Build build = buildsFor(account, region).get(buildId);
        if (build == null) {
            throw new AwsException("ResourceNotFoundException", "Build not found: " + buildId, 400);
        }
        return build;
    }

    public List<Build> batchGetBuilds(String region, String account, List<String> buildIds) {
        Map<String, Build> store = buildsFor(account, region);
        return buildIds.stream()
                .map(store::get)
                .filter(b -> b != null)
                .collect(Collectors.toList());
    }

    public Map<String, List<?>> batchDeleteBuilds(String region, String account, List<String> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > 100 || ids.stream().anyMatch(id -> id == null || id.isBlank())) {
            throw new AwsException("InvalidInputException", "ids must contain between 1 and 100 build IDs", 400);
        }
        List<String> deleted = new ArrayList<>();
        List<Map<String, String>> notDeleted = new ArrayList<>();
        Map<String, Build> store = buildsFor(account, region);
        for (String id : ids) {
            Build build = store.get(id);
            if (build == null && AwsArnUtils.isArnFor(id, "codebuild")) {
                build = store.values().stream().filter(candidate -> id.equals(candidate.getArn())).findFirst().orElse(null);
            }
            if (build == null) {
                notDeleted.add(Map.of("id", id, "statusCode", "RESOURCE_NOT_FOUND"));
            } else if (!Boolean.TRUE.equals(build.getBuildComplete())) {
                notDeleted.add(Map.of("id", id, "statusCode", "BUILD_IN_PROGRESS"));
            } else {
                store.remove(build.getId());
                buildspecOverridesFor(account, region).remove(build.getId());
                deleted.add(id);
            }
        }
        return Map.of("buildsDeleted", deleted, "buildsNotDeleted", notDeleted);
    }

    public List<String> listBuilds(String region, String account) {
        return buildsFor(account, region).values().stream()
                .sorted((a, b) -> Double.compare(
                        b.getStartTime() != null ? b.getStartTime() : 0,
                        a.getStartTime() != null ? a.getStartTime() : 0))
                .map(Build::getId)
                .collect(Collectors.toList());
    }

    public List<String> listBuildsForProject(String region, String account, String projectName) {
        return buildsFor(account, region).values().stream()
                .filter(b -> projectName.equals(b.getProjectName()))
                .sorted((a, b) -> Double.compare(
                        b.getStartTime() != null ? b.getStartTime() : 0,
                        a.getStartTime() != null ? a.getStartTime() : 0))
                .map(Build::getId)
                .collect(Collectors.toList());
    }

    public void stopBuild(String region, String account, String buildId) {
        Build build = buildsFor(account, region).get(buildId);
        if (build == null) {
            throw new AwsException("ResourceNotFoundException", "Build not found: " + buildId, 400);
        }
        runner.stopBuild(build.getArn());
    }

    public Build retryBuild(String region, String account, String buildId) {
        Build original = getBuild(region, account, buildId);
        String buildspecOverride = buildspecOverridesFor(account, region).get(original.getId());
        return startBuild(region, account, original.getProjectName(),
                buildspecOverride, original.getEnvironment(), original.getArtifacts(),
                null, original.getTimeoutInMinutes(), null, null);
    }

    private Build copyBuild(Build source) {
        Build copy = new Build();
        copy.setId(source.getId());
        copy.setArn(source.getArn());
        copy.setBuildNumber(source.getBuildNumber());
        copy.setBuildStatus(source.getBuildStatus());
        copy.setBuildComplete(source.getBuildComplete());
        copy.setCurrentPhase(source.getCurrentPhase());
        copy.setProjectName(source.getProjectName());
        copy.setInitiator(source.getInitiator());
        copy.setStartTime(source.getStartTime());
        copy.setEndTime(source.getEndTime());
        copy.setSource(source.getSource());
        copy.setArtifacts(source.getArtifacts());
        copy.setEnvironment(source.getEnvironment());
        copy.setLogs(source.getLogs());
        copy.setPhases(source.getPhases() != null ? new ArrayList<>(source.getPhases()) : null);
        copy.setTimeoutInMinutes(source.getTimeoutInMinutes());
        copy.setQueuedTimeoutInMinutes(source.getQueuedTimeoutInMinutes());
        copy.setEncryptionKey(source.getEncryptionKey());
        return copy;
    }
}
