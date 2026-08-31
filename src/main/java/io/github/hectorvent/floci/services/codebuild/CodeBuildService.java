package io.github.hectorvent.floci.services.codebuild;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.StorageBackedMap;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.codebuild.model.Build;
import io.github.hectorvent.floci.services.codebuild.model.BuildBatch;
import io.github.hectorvent.floci.services.codebuild.model.BuildPhase;
import io.github.hectorvent.floci.services.codebuild.model.CommandExecution;
import io.github.hectorvent.floci.services.codebuild.model.Project;
import io.github.hectorvent.floci.services.codebuild.model.ProjectArtifacts;
import io.github.hectorvent.floci.services.codebuild.model.ProjectEnvironment;
import io.github.hectorvent.floci.services.codebuild.model.ProjectSource;
import io.github.hectorvent.floci.services.codebuild.model.Report;
import io.github.hectorvent.floci.services.codebuild.model.ReportGroup;
import io.github.hectorvent.floci.services.codebuild.model.Sandbox;
import io.github.hectorvent.floci.services.codebuild.model.SourceCredential;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
    // key: region -> arn -> resource policy document
    private Map<String, Map<String, String>> resourcePolicies = new ConcurrentHashMap<>();
    // key: region -> buildId -> build (transient: builds are runtime state)
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Build>> builds = new ConcurrentHashMap<>();
    // key: region:projectName -> build counter (transient)
    private final ConcurrentHashMap<String, AtomicLong> buildCounters = new ConcurrentHashMap<>();
    // key: region -> batchId -> batch (transient)
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, BuildBatch>> buildBatches = new ConcurrentHashMap<>();
    // key: region -> sandboxId -> sandbox (transient)
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Sandbox>> sandboxes = new ConcurrentHashMap<>();
    // key: region -> commandId -> command execution (transient)
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, CommandExecution>> commandExecutions = new ConcurrentHashMap<>();
    // key: region -> reportArn -> report (transient)
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Report>> reports = new ConcurrentHashMap<>();

    private final CodeBuildRunner runner;
    private final EmulatorConfig config;
    private final StorageFactory storageFactory;

    @Inject
    public CodeBuildService(CodeBuildRunner runner, EmulatorConfig config, StorageFactory storageFactory) {
        this.runner = runner;
        this.config = config;
        this.storageFactory = storageFactory;
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
        this.resourcePolicies = storageBacked("codebuild-resource-policies.json",
                new TypeReference<Map<String, Map<String, String>>>() {});
        normalizeRegionMaps(projects);
        normalizeRegionMaps(reportGroups);
        normalizeRegionMaps(sourceCredentials);
        normalizeRegionMaps(resourcePolicies);
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

    private Map<String, String> resourcePoliciesFor(String region) {
        return resourcePolicies.computeIfAbsent(region, r -> new ConcurrentHashMap<>());
    }

    private Map<String, Build> buildsFor(String region) {
        return builds.computeIfAbsent(region, r -> new ConcurrentHashMap<>());
    }

    private Map<String, BuildBatch> buildBatchesFor(String region) {
        return buildBatches.computeIfAbsent(region, r -> new ConcurrentHashMap<>());
    }

    private Map<String, Sandbox> sandboxesFor(String region) {
        return sandboxes.computeIfAbsent(region, r -> new ConcurrentHashMap<>());
    }

    private Map<String, CommandExecution> commandExecutionsFor(String region) {
        return commandExecutions.computeIfAbsent(region, r -> new ConcurrentHashMap<>());
    }

    private Map<String, Report> reportsFor(String region) {
        return reports.computeIfAbsent(region, r -> new ConcurrentHashMap<>());
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
        applyEnvironmentDefaults(environment);
        project.setLogsConfig(logsConfig != null ? logsConfig : defaultLogsConfig());
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
        if (environment != null) {
            applyEnvironmentDefaults(environment);
            project.setEnvironment(environment);
        }
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

    public void deleteProject(String region, String name) {
        Map<String, Project> store = projectsFor(region);
        Project removed = store.remove(name);
        if (removed == null) {
            throw new AwsException("ResourceNotFoundException", "Project not found: " + name, 400);
        }
        if (removed.getArn() != null) {
            resourcePoliciesFor(region).remove(removed.getArn());
            persistRegion(resourcePolicies, region);
        }
        persistRegion(projects, region);
    }

    public List<Project> batchGetProjects(String region, List<String> names) {
        Map<String, Project> store = projectsFor(region);
        return names.stream()
                .map(store::get)
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
        Map<String, ReportGroup> store = reportGroupsFor(region);
        if (store.remove(arn) == null) {
            throw new AwsException("ResourceNotFoundException", "Report group not found: " + arn, 400);
        }
        resourcePoliciesFor(region).remove(arn);
        persistRegion(resourcePolicies, region);
        persistRegion(reportGroups, region);
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

    private static Map<String, Object> defaultLogsConfig() {
        Map<String, Object> logs = new LinkedHashMap<>();
        logs.put("cloudWatchLogs", Map.of("status", "ENABLED"));
        logs.put("s3Logs", Map.of("status", "DISABLED"));
        return logs;
    }

    private static void applyEnvironmentDefaults(ProjectEnvironment environment) {
        if (environment == null) {
            return;
        }
        if (environment.getType() == null || environment.getType().isBlank()) {
            environment.setType("LINUX_CONTAINER");
        }
        if (environment.getComputeType() == null || environment.getComputeType().isBlank()) {
            environment.setComputeType("BUILD_GENERAL1_SMALL");
        }
        if (environment.getEnvironmentVariables() != null) {
            for (Map<String, String> variable : environment.getEnvironmentVariables()) {
                if (variable.get("type") == null || variable.get("type").isBlank()) {
                    variable.put("type", "PLAINTEXT");
                }
            }
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

        String counterKey = region + ":" + projectName;
        long buildNumber = buildCounters
                .computeIfAbsent(counterKey, k -> new AtomicLong(0))
                .incrementAndGet();

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

        buildsFor(region).put(buildId, build);
        Build responseBuild = copyBuild(build);

        runner.startBuild(region, build, project, buildspecOverride);

        return responseBuild;
    }

    public Build getBuild(String region, String buildId) {
        Build build = buildsFor(region).get(buildId);
        if (build == null) {
            throw new AwsException("ResourceNotFoundException", "Build not found: " + buildId, 400);
        }
        return build;
    }

    public List<Build> batchGetBuilds(String region, List<String> buildIds) {
        Map<String, Build> store = buildsFor(region);
        return buildIds.stream()
                .map(store::get)
                .filter(b -> b != null)
                .collect(Collectors.toList());
    }

    public List<String> listBuilds(String region) {
        return buildsFor(region).values().stream()
                .sorted((a, b) -> Double.compare(
                        b.getStartTime() != null ? b.getStartTime() : 0,
                        a.getStartTime() != null ? a.getStartTime() : 0))
                .map(Build::getId)
                .collect(Collectors.toList());
    }

    public List<String> listBuildsForProject(String region, String projectName) {
        return buildsFor(region).values().stream()
                .filter(b -> projectName.equals(b.getProjectName()))
                .sorted((a, b) -> Double.compare(
                        b.getStartTime() != null ? b.getStartTime() : 0,
                        a.getStartTime() != null ? a.getStartTime() : 0))
                .map(Build::getId)
                .collect(Collectors.toList());
    }

    public Build stopBuild(String region, String buildId) {
        Build build = buildsFor(region).get(buildId);
        if (build == null) {
            throw new AwsException("ResourceNotFoundException", "Build not found: " + buildId, 400);
        }
        runner.stopBuild(buildId);
        // AWS StopBuild returns a terminal/stopping snapshot immediately; the
        // container teardown happens in the background and must not leave the
        // build stuck IN_PROGRESS if Docker is slow or the image is missing.
        if (!Boolean.TRUE.equals(build.getBuildComplete())) {
            build.setBuildStatus("STOPPED");
            build.setBuildComplete(true);
            build.setCurrentPhase("COMPLETED");
            build.setEndTime(Instant.now().toEpochMilli() / 1000.0);
        }
        return build;
    }

    public Build retryBuild(String region, String account, String buildId) {
        Build original = getBuild(region, buildId);
        return startBuild(region, account, original.getProjectName(),
                null, original.getEnvironment(), original.getArtifacts(),
                null, original.getTimeoutInMinutes(), null, null);
    }

    public void applyProjectOptionalFields(String region, String name,
                                           Map<String, Object> cache,
                                           Map<String, Object> buildBatchConfig) {
        Project project = projectsFor(region).get(name);
        if (project == null) {
            return;
        }
        if (cache != null) {
            project.setCache(cache);
        }
        if (buildBatchConfig != null) {
            project.setBuildBatchConfig(buildBatchConfig);
        }
        persistRegion(projects, region);
    }

    public Map<String, Object> batchDeleteBuilds(String region, List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new AwsException("InvalidInputException", "ids is required", 400);
        }
        List<String> deleted = new ArrayList<>();
        List<Map<String, String>> notDeleted = new ArrayList<>();
        Map<String, Build> store = buildsFor(region);
        for (String id : ids) {
            // Standalone (non-batch) builds cannot be deleted — AWS reports them
            // in buildsNotDeleted with INVALID_INPUT_EXCEPTION.
            Map<String, String> entry = new java.util.HashMap<>();
            entry.put("id", id);
            entry.put("statusCode", store.containsKey(id) ? "INVALID_INPUT_EXCEPTION" : "BUILD_NOT_FOUND");
            notDeleted.add(entry);
        }
        Map<String, Object> result = new java.util.HashMap<>();
        result.put("buildsDeleted", deleted);
        result.put("buildsNotDeleted", notDeleted);
        return result;
    }

    public void invalidateProjectCache(String region, String projectName) {
        requireProject(region, projectName);
    }

    public BuildBatch startBuildBatch(String region, String account, String projectName) {
        Project project = requireProject(region, projectName);
        if (project.getBuildBatchConfig() == null || project.getBuildBatchConfig().isEmpty()) {
            throw new AwsException("InvalidInputException",
                    "Batch build is not configured for this project", 400);
        }
        String id = projectName + ":" + UUID.randomUUID();
        BuildBatch batch = new BuildBatch();
        batch.setId(id);
        batch.setArn(AwsArnUtils.Arn.of("codebuild", region, account, "build-batch/" + id).toString());
        batch.setProjectName(projectName);
        batch.setBuildBatchStatus("IN_PROGRESS");
        batch.setComplete(false);
        batch.setCurrentPhase("SUBMITTED");
        batch.setStartTime(Instant.now().toEpochMilli() / 1000.0);
        batch.setBuildBatchNumber(1L);
        buildBatchesFor(region).put(id, batch);
        return batch;
    }

    public List<BuildBatch> batchGetBuildBatches(String region, List<String> ids) {
        Map<String, BuildBatch> store = buildBatchesFor(region);
        return ids.stream().map(store::get).filter(b -> b != null).collect(Collectors.toList());
    }

    public List<String> listBuildBatches(String region) {
        return new ArrayList<>(buildBatchesFor(region).keySet());
    }

    public List<String> listBuildBatchesForProject(String region, String projectName) {
        requireProject(region, projectName);
        return buildBatchesFor(region).values().stream()
                .filter(b -> projectName.equals(b.getProjectName()))
                .map(BuildBatch::getId)
                .collect(Collectors.toList());
    }

    public BuildBatch stopBuildBatch(String region, String id) {
        BuildBatch batch = buildBatchesFor(region).get(id);
        if (batch == null) {
            throw new AwsException("ResourceNotFoundException", "Build batch not found: " + id, 400);
        }
        batch.setBuildBatchStatus("STOPPED");
        batch.setComplete(true);
        batch.setCurrentPhase("STOPPED");
        batch.setEndTime(Instant.now().toEpochMilli() / 1000.0);
        return batch;
    }

    public BuildBatch retryBuildBatch(String region, String account, String id) {
        BuildBatch original = buildBatchesFor(region).get(id);
        if (original == null) {
            throw new AwsException("ResourceNotFoundException", "Build batch not found: " + id, 400);
        }
        return startBuildBatch(region, account, original.getProjectName());
    }

    public Map<String, Object> deleteBuildBatch(String region, String id) {
        BuildBatch batch = buildBatchesFor(region).remove(id);
        if (batch == null) {
            throw new AwsException("InvalidInputException", "Build batch not found: " + id, 400);
        }
        Map<String, Object> result = new java.util.HashMap<>();
        result.put("statusCode", "SUCCEEDED");
        result.put("buildsDeleted", List.of());
        result.put("buildsNotDeleted", List.of());
        return result;
    }

    public Sandbox startSandbox(String region, String account, String projectName) {
        requireProject(region, projectName);
        String id = projectName + ":" + UUID.randomUUID();
        double now = Instant.now().toEpochMilli() / 1000.0;
        Sandbox sandbox = new Sandbox();
        sandbox.setId(id);
        sandbox.setArn(AwsArnUtils.Arn.of("codebuild", region, account, "sandbox/" + id).toString());
        sandbox.setProjectName(projectName);
        sandbox.setStatus("RUNNING");
        sandbox.setRequestTime(now);
        sandbox.setStartTime(now);
        sandboxesFor(region).put(id, sandbox);
        return sandbox;
    }

    public Sandbox stopSandbox(String region, String id) {
        Sandbox sandbox = sandboxesFor(region).get(id);
        if (sandbox == null) {
            throw new AwsException("ResourceNotFoundException", "Sandbox not found: " + id, 400);
        }
        sandbox.setStatus("STOPPED");
        sandbox.setEndTime(Instant.now().toEpochMilli() / 1000.0);
        return sandbox;
    }

    public List<Sandbox> batchGetSandboxes(String region, List<String> ids) {
        Map<String, Sandbox> store = sandboxesFor(region);
        return ids.stream().map(store::get).filter(s -> s != null).collect(Collectors.toList());
    }

    public List<String> listSandboxes(String region) {
        return new ArrayList<>(sandboxesFor(region).keySet());
    }

    public List<String> listSandboxesForProject(String region, String projectName) {
        requireProject(region, projectName);
        return sandboxesFor(region).values().stream()
                .filter(s -> projectName.equals(s.getProjectName()))
                .map(Sandbox::getId)
                .collect(Collectors.toList());
    }

    public CommandExecution startCommandExecution(String region, String sandboxId, String command, String type) {
        Sandbox sandbox = sandboxesFor(region).get(sandboxId);
        if (sandbox == null) {
            throw new AwsException("ResourceNotFoundException", "Sandbox not found: " + sandboxId, 400);
        }
        if (command == null || command.isBlank()) {
            throw new AwsException("InvalidInputException", "command is required", 400);
        }
        double now = Instant.now().toEpochMilli() / 1000.0;
        CommandExecution execution = new CommandExecution();
        execution.setId(UUID.randomUUID().toString());
        execution.setSandboxId(sandboxId);
        execution.setSandboxArn(sandbox.getArn());
        execution.setCommand(command);
        execution.setType(type != null ? type : "SHELL");
        execution.setStatus("SUCCEEDED");
        execution.setSubmitTime(now);
        execution.setStartTime(now);
        execution.setEndTime(now);
        execution.setExitCode("0");
        commandExecutionsFor(region).put(execution.getId(), execution);
        return execution;
    }

    public List<CommandExecution> batchGetCommandExecutions(String region, String sandboxId, List<String> ids) {
        Map<String, CommandExecution> store = commandExecutionsFor(region);
        return ids.stream()
                .map(store::get)
                .filter(c -> c != null && (sandboxId == null || sandboxId.equals(c.getSandboxId())))
                .collect(Collectors.toList());
    }

    public List<CommandExecution> listCommandExecutionsForSandbox(String region, String sandboxId) {
        Sandbox sandbox = sandboxesFor(region).get(sandboxId);
        if (sandbox == null) {
            throw new AwsException("ResourceNotFoundException", "Sandbox not found: " + sandboxId, 400);
        }
        return commandExecutionsFor(region).values().stream()
                .filter(c -> sandboxId.equals(c.getSandboxId()))
                .collect(Collectors.toList());
    }

    public List<String> listReportsForReportGroup(String region, String reportGroupArn) {
        if (reportGroupsFor(region).get(reportGroupArn) == null) {
            throw new AwsException("ResourceNotFoundException",
                    "Report group not found: " + reportGroupArn, 400);
        }
        return reportsFor(region).values().stream()
                .filter(r -> reportGroupArn.equals(r.getReportGroupArn()))
                .map(Report::getArn)
                .collect(Collectors.toList());
    }

    public List<Report> batchGetReports(String region, List<String> arns) {
        Map<String, Report> store = reportsFor(region);
        return arns.stream().map(store::get).filter(r -> r != null).collect(Collectors.toList());
    }

    public void describeTestCases(String region, String reportArn) {
        // Unknown reports return an empty list (AWS DescribeTestCases is
        // report-scoped; missing reports are not a hard error here).
        if (reportArn == null || reportArn.isBlank()) {
            throw new AwsException("InvalidInputException", "reportArn is required", 400);
        }
    }

    public void describeCodeCoverages(String region, String reportArn) {
        if (reportArn == null || reportArn.isBlank()) {
            throw new AwsException("InvalidInputException", "reportArn is required", 400);
        }
    }

    public Map<String, Object> getReportGroupTrend(String region, String reportGroupArn, String trendField) {
        if (reportGroupArn == null || reportGroupArn.isBlank()) {
            throw new AwsException("InvalidInputException", "reportGroupArn is required", 400);
        }
        if (reportGroupsFor(region).get(reportGroupArn) == null) {
            throw new AwsException("ResourceNotFoundException",
                    "Report group not found: " + reportGroupArn, 400);
        }
        if (trendField == null || trendField.isBlank()) {
            throw new AwsException("InvalidInputException", "trendField is required", 400);
        }
        Map<String, Object> stats = new java.util.HashMap<>();
        stats.put("average", "0");
        stats.put("max", "0");
        stats.put("min", "0");
        Map<String, Object> result = new java.util.HashMap<>();
        result.put("stats", stats);
        result.put("rawData", List.of());
        return result;
    }

    public void deleteReport(String region, String arn) {
        if (arn == null || arn.isBlank()) {
            throw new AwsException("InvalidInputException", "arn is required", 400);
        }
        reportsFor(region).remove(arn);
    }

    public String getResourcePolicy(String region, String resourceArn) {
        requirePolicyResource(region, resourceArn);
        String policy = resourcePoliciesFor(region).get(resourceArn);
        if (policy == null || policy.isBlank()) {
            throw new AwsException("ResourceNotFoundException",
                    "Resource policy not found for " + resourceArn, 400);
        }
        return policy;
    }

    public String putResourcePolicy(String region, String resourceArn, String policy) {
        requirePolicyResource(region, resourceArn);
        if (policy == null || policy.isBlank()) {
            throw new AwsException("InvalidInputException", "policy is required", 400);
        }
        resourcePoliciesFor(region).put(resourceArn, policy);
        persistRegion(resourcePolicies, region);
        return resourceArn;
    }

    public void deleteResourcePolicy(String region, String resourceArn) {
        requirePolicyResource(region, resourceArn);
        String removed = resourcePoliciesFor(region).remove(resourceArn);
        if (removed == null) {
            throw new AwsException("ResourceNotFoundException",
                    "Resource policy not found for " + resourceArn, 400);
        }
        persistRegion(resourcePolicies, region);
    }

    private Project requireProject(String region, String projectName) {
        if (projectName == null || projectName.isBlank()) {
            throw new AwsException("InvalidInputException", "projectName is required", 400);
        }
        Project project = projectsFor(region).get(projectName);
        if (project == null) {
            throw new AwsException("ResourceNotFoundException", "Project not found: " + projectName, 400);
        }
        return project;
    }

    private void requirePolicyResource(String region, String resourceArn) {
        if (resourceArn == null || resourceArn.isBlank()) {
            throw new AwsException("InvalidInputException", "resourceArn is required", 400);
        }
        int projectIdx = resourceArn.indexOf(":project/");
        if (projectIdx >= 0) {
            String name = resourceArn.substring(projectIdx + ":project/".length());
            if (projectsFor(region).containsKey(name)) {
                return;
            }
        }
        if (resourceArn.contains(":report-group/") && reportGroupsFor(region).containsKey(resourceArn)) {
            return;
        }
        throw new AwsException("ResourceNotFoundException", "Resource not found: " + resourceArn, 400);
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
