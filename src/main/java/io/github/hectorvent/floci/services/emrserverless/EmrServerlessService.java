package io.github.hectorvent.floci.services.emrserverless;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.emrserverless.model.Application;
import io.github.hectorvent.floci.services.emrserverless.model.ApplicationSummary;
import io.github.hectorvent.floci.services.emrserverless.model.CreateApplicationRequest;
import io.github.hectorvent.floci.services.emrserverless.model.ListApplicationsRequest;
import io.github.hectorvent.floci.services.emrserverless.model.UpdateApplicationRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class EmrServerlessService {

    private final EmulatorConfig config;
    private final AccountAwareStorageBackend<Application> storage;
    private final EmrServerlessJobService jobs;
    
    @Inject
    RequestContext requestContext;

    @Inject
    public EmrServerlessService(EmulatorConfig config, StorageFactory storageFactory, EmrServerlessJobService jobs) {
        this.config = config;
        this.jobs = jobs;
        this.storage = storageFactory.create("emrserverless", "emr-serverless-applications.json",
                new TypeReference<Map<String, Application>>() {});
    }

    public synchronized Application createApplication(CreateApplicationRequest request) {
        if (request.getReleaseLabel() == null || request.getReleaseLabel().isBlank()) {
            throw new AwsException("ValidationException", "releaseLabel is required", 400);
        }
        if (request.getType() == null || request.getType().isBlank()) {
            throw new AwsException("ValidationException", "type is required", 400);
        }
        String type;
        if ("SPARK".equalsIgnoreCase(request.getType())) {
            type = "Spark";
        } else if ("HIVE".equalsIgnoreCase(request.getType())) {
            type = "Hive";
        } else {
            throw new AwsException("ValidationException", "type must be SPARK or HIVE", 400);
        }
        if (request.getClientToken() == null || request.getClientToken().isBlank()) {
            throw new AwsException("ValidationException", "clientToken is required", 400);
        }

        if (request.getClientToken() != null) {
            for (Application existing : applicationsInRegion()) {
                if (request.getClientToken().equals(existing.getClientToken())) {
                    return existing;
                }
            }
        }

        String id = generateId();
        String arn = buildArn(id);
        long now = System.currentTimeMillis();

        Application app = new Application();
        app.setApplicationId(id);
        app.setClientToken(request.getClientToken());
        app.setArn(arn);
        app.setName(request.getName());
        app.setReleaseLabel(request.getReleaseLabel());
        app.setType(type);
        app.setState("CREATED");
        app.setStateDetails("");
        app.setCreatedAt(now);
        app.setUpdatedAt(now);
        app.setTags(request.getTags());
        app.setArchitecture(request.getArchitecture());
        app.setInitialCapacity(request.getInitialCapacity());
        app.setMaximumCapacity(request.getMaximumCapacity());
        app.setAutoStartConfiguration(request.getAutoStartConfiguration());
        app.setAutoStopConfiguration(request.getAutoStopConfiguration());
        app.setNetworkConfiguration(request.getNetworkConfiguration());
        app.setImageConfiguration(request.getImageConfiguration());
        app.setInteractiveConfiguration(request.getInteractiveConfiguration());
        app.setWorkerTypeSpecifications(request.getWorkerTypeSpecifications());

        storage.putForAccount(accountId(), storageKey(id), app);
        return app;
    }

    public Application getApplication(String applicationId) {
        return storage.getForAccountMigratingLegacyKeys(accountId(), storageKey(applicationId),
                        List.of(applicationId), app -> buildArn(applicationId).equals(app.getArn()))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Application " + applicationId + " not found", 404));
    }

    public PaginatedResult<ApplicationSummary> listApplications(ListApplicationsRequest request) {
        List<Application> all = applicationsInRegion();
        if (request.getStates() != null && !request.getStates().isEmpty()) {
            all = all.stream().filter(app -> request.getStates().contains(app.getState())).collect(Collectors.toList());
        }
        
        PaginatedResult<Application> page = Pagination.paginate(all, Application::getApplicationId, request.getMaxResults(), request.getNextToken(), 50, "ValidationException");
        
        return new PaginatedResult<>(
                page.items().stream().map(this::toSummary).collect(Collectors.toList()),
                page.nextToken()
        );
    }

    public synchronized Application updateApplication(String applicationId, UpdateApplicationRequest request) {
        Application app = getApplication(applicationId);
        
        String state = app.getState();
        if (!"CREATED".equals(state) && !"STOPPED".equals(state)) {
            throw new AwsException("ValidationException", "Application must be in a stopped or created state in order to be updated.", 400);
        }

        if (request.getReleaseLabel() != null) {
            app.setReleaseLabel(request.getReleaseLabel());
        }
        if (request.getInitialCapacity() != null) {
            app.setInitialCapacity(request.getInitialCapacity());
        }
        if (request.getMaximumCapacity() != null) {
            app.setMaximumCapacity(request.getMaximumCapacity());
        }
        if (request.getAutoStartConfiguration() != null) {
            app.setAutoStartConfiguration(request.getAutoStartConfiguration());
        }
        if (request.getAutoStopConfiguration() != null) {
            app.setAutoStopConfiguration(request.getAutoStopConfiguration());
        }
        if (request.getNetworkConfiguration() != null) {
            app.setNetworkConfiguration(request.getNetworkConfiguration());
        }
        if (request.getArchitecture() != null) {
            app.setArchitecture(request.getArchitecture());
        }
        if (request.getImageConfiguration() != null) {
            app.setImageConfiguration(request.getImageConfiguration());
        }
        if (request.getWorkerTypeSpecifications() != null) {
            app.setWorkerTypeSpecifications(request.getWorkerTypeSpecifications());
        }
        if (request.getMonitoringConfiguration() != null) {
            app.setMonitoringConfiguration(request.getMonitoringConfiguration());
        }
        if (request.getRuntimeConfiguration() != null) {
            app.setRuntimeConfiguration(request.getRuntimeConfiguration());
        }
        if (request.getSchedulerConfiguration() != null) {
            app.setSchedulerConfiguration(request.getSchedulerConfiguration());
        }
        if (request.getDiskEncryptionConfiguration() != null) {
            app.setDiskEncryptionConfiguration(request.getDiskEncryptionConfiguration());
        }
        if (request.getInteractiveConfiguration() != null) {
            app.setInteractiveConfiguration(request.getInteractiveConfiguration());
        }
        if (request.getIdentityCenterConfiguration() != null) {
            app.setIdentityCenterConfiguration(request.getIdentityCenterConfiguration());
        }
        if (request.getJobLevelCostAllocationConfiguration() != null) {
            app.setJobLevelCostAllocationConfiguration(request.getJobLevelCostAllocationConfiguration());
        }

        app.setUpdatedAt(System.currentTimeMillis());
        storage.putForAccount(accountId(), storageKey(applicationId), app);
        return app;
    }

    public synchronized void deleteApplication(String applicationId) {
        Application app = getApplication(applicationId);
        String state = app.getState();
        if (!"CREATED".equals(state) && !"STOPPED".equals(state)) {
            throw new AwsException("ValidationException", "Application must be in a stopped or created state in order to be deleted.", 400);
        }
        jobs.deleteApplicationJobs(accountId(), region(), applicationId);
        storage.deleteForAccount(accountId(), storageKey(applicationId));
    }

    public synchronized void startApplication(String applicationId) {
        Application app = getApplication(applicationId);
        String state = app.getState();
        if ("STARTED".equals(state) || "STARTING".equals(state)) {
            return;
        }
        app.setState("STARTED");
        app.setUpdatedAt(System.currentTimeMillis());
        storage.putForAccount(accountId(), storageKey(applicationId), app);
    }

    public synchronized void stopApplication(String applicationId) {
        Application app = getApplication(applicationId);
        jobs.requireIdle(accountId(), region(), applicationId);
        String state = app.getState();
        if ("STOPPED".equals(state) || "STOPPING".equals(state)) {
            return;
        }
        app.setState("STOPPED");
        app.setUpdatedAt(System.currentTimeMillis());
        storage.putForAccount(accountId(), storageKey(applicationId), app);
    }

    public PaginatedResult<ObjectNode> listJobRuns(String applicationId, Integer maxResults, String nextToken,
                                                   List<String> states, Double after, Double before, String mode) {
        getApplication(applicationId);
        return jobs.list(accountId(), region(), applicationId, maxResults, nextToken, states, after, before, mode);
    }

    public List<Map<String, Object>> listSessions(String applicationId, Integer maxResults, String nextToken) {
        getApplication(applicationId);
        // Session submission remains unsupported; batch job runs are a separate collection.
        return Pagination.paginate(List.<Map<String, Object>>of(), item -> "",
                maxResults, nextToken, 50, "ValidationException").items();
    }

    public synchronized ObjectNode startJobRun(String applicationId, JsonNode request, String authorization) {
        Application app = getApplication(applicationId);
        requireExecutionRequest(request);
        if (!List.of("CREATED", "STOPPED", "STARTED").contains(app.getState())) {
            throw new AwsException("ValidationException", "Application is not available for job submission", 400);
        }
        if (!"STARTED".equals(app.getState()) && app.getAutoStartConfiguration() != null
                && Boolean.FALSE.equals(app.getAutoStartConfiguration().getEnabled())) {
            throw new AwsException("ValidationException", "Start the application before submitting a job when auto-start is disabled", 400);
        }
        ObjectNode result = jobs.start(app, request, accountId(), region(), authorization);
        if (!EmrServerlessJobService.terminal(jobs.get(accountId(), region(), applicationId,
                result.path("jobRunId").asText(), null)) && !"STARTED".equals(app.getState())) {
            app.setState("STARTED");
            app.setUpdatedAt(System.currentTimeMillis());
            storage.putForAccount(accountId(), storageKey(applicationId), app);
        }
        return result;
    }

    public ObjectNode getJobRun(String applicationId, String jobRunId, String attempt) {
        getApplication(applicationId);
        return jobs.get(accountId(), region(), applicationId, jobRunId, attempt);
    }

    public ObjectNode cancelJobRun(String applicationId, String jobRunId) {
        getApplication(applicationId);
        return jobs.cancel(accountId(), region(), applicationId, jobRunId);
    }

    public PaginatedResult<ObjectNode> listJobRunAttempts(String applicationId, String jobRunId,
                                                         Integer maxResults, String nextToken) {
        getApplication(applicationId);
        return jobs.attempts(accountId(), region(), applicationId, jobRunId, maxResults, nextToken);
    }

    public void getDashboardForJobRun(String applicationId, String jobRunId) {
        getJobRun(applicationId, jobRunId, null);
        throw new AwsException("UnsupportedOperationException", "Spark dashboards are not implemented", 501);
    }

    public void startSession(String applicationId, JsonNode request) {
        Application app = getApplication(applicationId);
        requireExecutionRequest(request);
        if (app.getInteractiveConfiguration() == null
                || !Boolean.TRUE.equals(app.getInteractiveConfiguration().getProperties().get("sessionEnabled"))) {
            throw new AwsException("ValidationException",
                    "Sessions must be enabled in the application's interactiveConfiguration.", 400);
        }
        throw new AwsException("UnsupportedOperationException",
                "EMR Serverless session execution is not implemented.", 501);
    }

    public void getResourceDashboard(String applicationId, String resourceId, String resourceType) {
        getApplication(applicationId);
        if (resourceId == null || resourceId.isBlank() || resourceType == null || resourceType.isBlank()) {
            throw new AwsException("ValidationException", "resourceId and resourceType are required", 400);
        }
        throw new AwsException("UnsupportedOperationException",
                "EMR Serverless resource dashboards are not implemented.", 501);
    }

    private void requireExecutionRequest(JsonNode request) {
        if (request == null || !request.isObject()) {
            throw new AwsException("ValidationException", "A request body is required", 400);
        }
        for (String field : List.of("clientToken", "executionRoleArn")) {
            if (!request.path(field).isTextual() || request.path(field).textValue().isBlank()) {
                throw new AwsException("ValidationException", field + " is required", 400);
            }
        }
    }

    private List<Application> applicationsInRegion() {
        storage.migrateLegacyEntries(accountId(), key -> !key.contains("/"),
                app -> storageKey(app.getApplicationId()),
                app -> buildArn(app.getApplicationId()).equals(app.getArn()));
        return storage.scanForAccount(accountId(), key -> key.startsWith(region() + "/")).stream()
                .filter(app -> buildArn(app.getApplicationId()).equals(app.getArn()))
                .toList();
    }

    private String storageKey(String applicationId) {
        return region() + "/" + applicationId;
    }

    private String region() {
        return requestContext != null && requestContext.getRegion() != null
                ? requestContext.getRegion() : config.defaultRegion();
    }

    private String accountId() {
        return requestContext != null && requestContext.getAccountId() != null
                ? requestContext.getAccountId() : config.defaultAccountId();
    }

    private String generateId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private String buildArn(String id) {
        return String.format("arn:aws:emr-serverless:%s:%s:/applications/%s",
                region(), accountId(), id);
    }

    private ApplicationSummary toSummary(Application app) {
        ApplicationSummary summary = new ApplicationSummary();
        summary.setId(app.getApplicationId());
        summary.setArn(app.getArn());
        summary.setName(app.getName());
        summary.setReleaseLabel(app.getReleaseLabel());
        summary.setType(app.getType());
        summary.setState(app.getState());
        summary.setStateDetails(app.getStateDetails());
        summary.setCreatedAt(app.getCreatedAt());
        summary.setUpdatedAt(app.getUpdatedAt());
        summary.setArchitecture(app.getArchitecture());
        return summary;
    }
}
