package io.github.hectorvent.floci.services.emrserverless;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.emrserverless.model.Application;
import io.github.hectorvent.floci.services.emrserverless.model.JobRun;
import io.github.hectorvent.floci.services.emrserverless.model.Session;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Amazon EMR Serverless restJson1 — application lifecycle.
 *
 * <p>Applications become {@code CREATED} immediately so local stacks do not wait on
 * the live-AWS asynchronous provisioning window. Tag APIs share {@code /tags/{arn}}
 * and are dispatched by {@code SharedTagsController} using ARN service
 * {@code emr-serverless}.
 */
@ApplicationScoped
public class EmrServerlessService implements TagHandler {

    static final String SERVICE = "emr-serverless";
    private static final String STATE_CREATED = "CREATED";
    private static final String STATE_STARTED = "STARTED";
    private static final String STATE_STOPPED = "STOPPED";
    private static final String STATE_STARTING = "STARTING";
    private static final String DEFAULT_ARCHITECTURE = "X86_64";
    private static final int DEFAULT_IDLE_TIMEOUT_MINUTES = 15;
    private static final Set<String> MUTABLE_STATES = Set.of(STATE_CREATED, STATE_STOPPED);
    private static final Set<String> TERMINAL_JOB_STATES = Set.of("SUCCESS", "FAILED", "CANCELLED");
    private static final Set<String> CANCELLABLE_JOB_STATES =
            Set.of("SUBMITTED", "PENDING", "SCHEDULED", "RUNNING", "QUEUED");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StorageBackend<String, Application> applications;
    private final StorageBackend<String, JobRun> jobRuns;
    private final StorageBackend<String, Session> sessions;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public EmrServerlessService(
            StorageFactory storageFactory, RegionResolver regionResolver, ObjectMapper objectMapper) {
        this(
                storageFactory.create("emrserverless", "emr-serverless-applications.json",
                        new TypeReference<Map<String, Application>>() {
                        }),
                storageFactory.create("emrserverless", "emr-serverless-job-runs.json",
                        new TypeReference<Map<String, JobRun>>() {
                        }),
                storageFactory.create("emrserverless", "emr-serverless-sessions.json",
                        new TypeReference<Map<String, Session>>() {
                        }),
                regionResolver,
                objectMapper);
    }

    EmrServerlessService(StorageBackend<String, Application> applications, RegionResolver regionResolver) {
        this(applications, new InMemoryStorage<>(), new InMemoryStorage<>(), regionResolver, new ObjectMapper());
    }

    EmrServerlessService(
            StorageBackend<String, Application> applications,
            RegionResolver regionResolver,
            ObjectMapper objectMapper) {
        this(applications, new InMemoryStorage<>(), new InMemoryStorage<>(), regionResolver, objectMapper);
    }

    EmrServerlessService(
            StorageBackend<String, Application> applications,
            StorageBackend<String, JobRun> jobRuns,
            StorageBackend<String, Session> sessions,
            RegionResolver regionResolver,
            ObjectMapper objectMapper) {
        this.applications = applications;
        this.jobRuns = jobRuns;
        this.sessions = sessions;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    public synchronized Application createApplication(String region, JsonNode request) {
        requireObject(request, "Request body");
        String clientToken = requireText(request, "clientToken");
        Application existingToken = findByClientToken(region, clientToken);
        if (existingToken != null) {
            return existingToken;
        }
        String name = optionalText(request, "name");
        if (name != null && findByName(region, name) != null) {
            throw conflict(name, "Application " + name + " already exists.");
        }
        String releaseLabel = requireText(request, "releaseLabel");
        String type = echoType(optionalText(request, "type"));
        String architecture = optionalText(request, "architecture");
        if (architecture == null) {
            architecture = DEFAULT_ARCHITECTURE;
        }
        long now = Instant.now().getEpochSecond();
        String applicationId = newApplicationId();
        String account = regionResolver.getAccountId();

        Application application = new Application();
        application.setApplicationId(applicationId);
        application.setName(name);
        application.setArn(applicationArn(region, account, applicationId));
        application.setReleaseLabel(releaseLabel);
        application.setType(type);
        application.setState(STATE_CREATED);
        application.setArchitecture(architecture);
        application.setCreatedAt(now);
        application.setUpdatedAt(now);
        application.setClientToken(clientToken);
        application.setRegion(region);
        applyAutoStart(application, request.get("autoStartConfiguration"), true);
        applyAutoStop(application, request.get("autoStopConfiguration"), true);
        application.setInitialCapacity(asObjectMap(request.get("initialCapacity")));
        application.setMaximumCapacity(asObjectMap(request.get("maximumCapacity")));
        application.setNetworkConfiguration(asObjectMap(request.get("networkConfiguration")));
        application.setInteractiveConfiguration(asObjectMap(request.get("interactiveConfiguration")));
        application.setTags(readTags(request.get("tags")));
        applications.put(applicationKey(region, applicationId), application);
        return application;
    }

    public Application getApplication(String region, String applicationId) {
        return requireById(region, applicationId);
    }

    public List<Application> listApplications(String region, List<String> states) {
        List<Application> items = applications.scan(key -> key.startsWith(region + "::"));
        if (states != null && !states.isEmpty()) {
            items.removeIf(application -> !states.contains(application.getState()));
        }
        items.sort(Comparator.comparing(Application::getCreatedAt)
                .thenComparing(Application::getApplicationId, Comparator.nullsLast(String::compareTo)));
        return items;
    }

    public synchronized Application updateApplication(String region, String applicationId, JsonNode request) {
        requireObject(request, "Request body");
        requireText(request, "clientToken");
        Application application = requireById(region, applicationId);
        requireMutable(application, "updated");
        if (request.hasNonNull("releaseLabel")) {
            application.setReleaseLabel(requireText(request, "releaseLabel"));
        }
        if (request.hasNonNull("architecture")) {
            application.setArchitecture(requireText(request, "architecture"));
        }
        if (request.has("autoStartConfiguration")) {
            applyAutoStart(application, request.get("autoStartConfiguration"), false);
        }
        if (request.has("autoStopConfiguration")) {
            applyAutoStop(application, request.get("autoStopConfiguration"), false);
        }
        if (request.has("initialCapacity")) {
            application.setInitialCapacity(asObjectMap(request.get("initialCapacity")));
        }
        if (request.has("maximumCapacity")) {
            application.setMaximumCapacity(asObjectMap(request.get("maximumCapacity")));
        }
        if (request.has("networkConfiguration")) {
            application.setNetworkConfiguration(asObjectMap(request.get("networkConfiguration")));
        }
        if (request.has("interactiveConfiguration")) {
            application.setInteractiveConfiguration(asObjectMap(request.get("interactiveConfiguration")));
        }
        application.setUpdatedAt(Instant.now().getEpochSecond());
        applications.put(applicationKey(region, application.getApplicationId()), application);
        return application;
    }

    public synchronized void deleteApplication(String region, String applicationId) {
        Application application = requireById(region, applicationId);
        requireMutable(application, "deleted");
        applications.delete(applicationKey(region, application.getApplicationId()));
    }

    public synchronized Application startApplication(String region, String applicationId) {
        Application application = requireById(region, applicationId);
        application.setState(STATE_STARTED);
        application.setUpdatedAt(Instant.now().getEpochSecond());
        applications.put(applicationKey(region, application.getApplicationId()), application);
        return application;
    }

    public synchronized Application stopApplication(String region, String applicationId) {
        Application application = requireById(region, applicationId);
        for (JobRun jobRun : listJobRuns(region, applicationId, null)) {
            if (!TERMINAL_JOB_STATES.contains(jobRun.getState())) {
                throw validation("Application " + applicationId
                        + " cannot be stopped while job run " + jobRun.getJobRunId()
                        + " is in " + jobRun.getState() + " state.");
            }
        }
        if (STATE_STARTING.equals(application.getState()) || STATE_STARTED.equals(application.getState())) {
            application.setState(STATE_STOPPED);
        }
        application.setUpdatedAt(Instant.now().getEpochSecond());
        applications.put(applicationKey(region, application.getApplicationId()), application);
        return application;
    }

    public synchronized JobRun startJobRun(String region, String applicationId, JsonNode request) {
        requireObject(request, "Request body");
        Application application = requireById(region, applicationId);
        String executionRoleArn = requireText(request, "executionRoleArn");
        String clientToken = optionalText(request, "clientToken");
        if (clientToken == null) {
            clientToken = UUID.randomUUID().toString();
        }
        JobRun existing = findJobRunByClientToken(region, applicationId, clientToken);
        if (existing != null) {
            return existing;
        }
        if (STATE_CREATED.equals(application.getState()) || STATE_STOPPED.equals(application.getState())) {
            if (Boolean.FALSE.equals(application.getAutoStartEnabled())) {
                throw validation("Application " + applicationId + " must be started before submitting a job run.");
            }
            startApplication(region, applicationId);
            application = requireById(region, applicationId);
        }
        long now = Instant.now().getEpochSecond();
        String jobRunId = newEmrId();
        String account = regionResolver.getAccountId();
        JobRun jobRun = new JobRun();
        jobRun.setApplicationId(applicationId);
        jobRun.setJobRunId(jobRunId);
        jobRun.setName(optionalText(request, "name"));
        jobRun.setArn(jobRunArn(region, account, applicationId, jobRunId));
        jobRun.setCreatedBy(executionRoleArn);
        jobRun.setCreatedAt(now);
        jobRun.setUpdatedAt(now);
        jobRun.setExecutionRole(executionRoleArn);
        jobRun.setState("PENDING");
        jobRun.setStateDetails("Job run is pending.");
        jobRun.setReleaseLabel(application.getReleaseLabel());
        jobRun.setJobDriver(asObjectMap(request.get("jobDriver")) == null
                ? Map.of()
                : asObjectMap(request.get("jobDriver")));
        jobRun.setTags(readTags(request.get("tags")));
        if (request.hasNonNull("executionTimeoutMinutes")) {
            jobRun.setExecutionTimeoutMinutes(request.get("executionTimeoutMinutes").asInt());
        }
        jobRun.setRegion(region);
        jobRun.setClientToken(clientToken);
        jobRuns.put(jobRunKey(region, applicationId, jobRunId), jobRun);
        return jobRun;
    }

    public JobRun getJobRun(String region, String applicationId, String jobRunId) {
        requireById(region, applicationId);
        return requireJobRun(region, applicationId, jobRunId);
    }

    public List<JobRun> listJobRuns(String region, String applicationId, List<String> states) {
        requireById(region, applicationId);
        String prefix = jobRunPrefix(region, applicationId);
        List<JobRun> items = jobRuns.scan(key -> key.startsWith(prefix));
        if (states != null && !states.isEmpty()) {
            items.removeIf(jobRun -> !states.contains(jobRun.getState()));
        }
        items.sort(Comparator.comparing(JobRun::getCreatedAt).reversed()
                .thenComparing(JobRun::getJobRunId, Comparator.nullsLast(String::compareTo)));
        return items;
    }

    public synchronized JobRun cancelJobRun(String region, String applicationId, String jobRunId) {
        requireById(region, applicationId);
        JobRun jobRun = requireJobRun(region, applicationId, jobRunId);
        if (TERMINAL_JOB_STATES.contains(jobRun.getState())) {
            return jobRun;
        }
        if (!CANCELLABLE_JOB_STATES.contains(jobRun.getState())) {
            throw validation("Job run " + jobRunId + " cannot be cancelled in " + jobRun.getState() + " state.");
        }
        jobRun.setState("CANCELLED");
        jobRun.setStateDetails("Job run cancelled.");
        jobRun.setUpdatedAt(Instant.now().getEpochSecond());
        jobRuns.put(jobRunKey(region, applicationId, jobRunId), jobRun);
        return jobRun;
    }

    public ObjectNode getDashboardForJobRun(String region, String applicationId, String jobRunId) {
        JobRun jobRun = getJobRun(region, applicationId, jobRunId);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("url", "https://emr-serverless.local/" + applicationId + "/jobruns/"
                + jobRun.getJobRunId() + "/dashboard");
        return response;
    }

    public ObjectNode listJobRunAttempts(String region, String applicationId, String jobRunId) {
        JobRun jobRun = getJobRun(region, applicationId, jobRunId);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode attempts = response.putArray("jobRunAttempts");
        attempts.add(toJobRunAttempt(jobRun));
        return response;
    }

    /**
     * Live AWS denies {@code emr-serverless:GetResourceDashboard} for every API
     * caller — even {@code Action: "*"} — because the operation backs console
     * dashboards and has not launched publicly. The denial is service-side
     * (no resource in the message), so a missing application is still AccessDenied.
     */
    public void getResourceDashboard(String region, String applicationId) {
        throw new AwsException(
                "AccessDeniedException",
                "User: arn:aws:iam::" + regionResolver.getAccountId()
                        + ":user/floci is not authorized to perform: emr-serverless:GetResourceDashboard",
                403);
    }

    public synchronized Session startSession(String region, String applicationId, JsonNode request) {
        requireObject(request, "Request body");
        Application application = requireById(region, applicationId);
        requireText(request, "executionRoleArn");
        if (!interactiveEnabled(application)) {
            throw validation("Application " + applicationId
                    + " does not have interactive features enabled.");
        }
        String executionRoleArn = requireText(request, "executionRoleArn");
        String clientToken = optionalText(request, "clientToken");
        if (clientToken == null) {
            clientToken = UUID.randomUUID().toString();
        }
        long now = Instant.now().getEpochSecond();
        String sessionId = newEmrId();
        String account = regionResolver.getAccountId();
        Session session = new Session();
        session.setApplicationId(applicationId);
        session.setSessionId(sessionId);
        session.setArn(sessionArn(region, account, applicationId, sessionId));
        session.setName(optionalText(request, "name"));
        session.setState("STARTED");
        session.setStateDetails("Session started.");
        session.setReleaseLabel(application.getReleaseLabel());
        session.setExecutionRoleArn(executionRoleArn);
        session.setCreatedBy(executionRoleArn);
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        session.setTags(readTags(request.get("tags")));
        session.setRegion(region);
        session.setClientToken(clientToken);
        sessions.put(sessionKey(region, applicationId, sessionId), session);
        return session;
    }

    public Session getSession(String region, String applicationId, String sessionId) {
        requireById(region, applicationId);
        return requireSession(region, applicationId, sessionId);
    }

    public List<Session> listSessions(String region, String applicationId, List<String> states) {
        requireById(region, applicationId);
        String prefix = sessionPrefix(region, applicationId);
        List<Session> items = sessions.scan(key -> key.startsWith(prefix));
        if (states != null && !states.isEmpty()) {
            items.removeIf(session -> !states.contains(session.getState()));
        }
        items.sort(Comparator.comparing(Session::getCreatedAt).reversed()
                .thenComparing(Session::getSessionId, Comparator.nullsLast(String::compareTo)));
        return items;
    }

    public synchronized Session terminateSession(String region, String applicationId, String sessionId) {
        Session session = getSession(region, applicationId, sessionId);
        session.setState("TERMINATED");
        session.setStateDetails("Session terminated.");
        session.setUpdatedAt(Instant.now().getEpochSecond());
        sessions.put(sessionKey(region, applicationId, sessionId), session);
        return session;
    }

    public ObjectNode getSessionEndpoint(String region, String applicationId, String sessionId) {
        Session session = getSession(region, applicationId, sessionId);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("applicationId", applicationId);
        response.put("sessionId", session.getSessionId());
        response.put("endpoint", "https://emr-serverless.local/" + applicationId
                + "/sessions/" + session.getSessionId());
        response.put("authToken", "emr-serverless-session-token");
        response.put("authTokenExpiresAt", Instant.now().getEpochSecond() + 3600);
        return response;
    }

    public ObjectNode toStartJobRun(JobRun jobRun) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("applicationId", jobRun.getApplicationId());
        response.put("jobRunId", jobRun.getJobRunId());
        response.put("arn", jobRun.getArn());
        return response;
    }

    public ObjectNode toJobRunEnvelope(JobRun jobRun) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("jobRun", toJobRun(jobRun));
        return response;
    }

    public ObjectNode toListJobRuns(List<JobRun> items) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("jobRuns");
        for (JobRun jobRun : items) {
            list.add(toJobRunSummary(jobRun));
        }
        return response;
    }

    public ObjectNode toCancelJobRun(JobRun jobRun) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("applicationId", jobRun.getApplicationId());
        response.put("jobRunId", jobRun.getJobRunId());
        return response;
    }

    public ObjectNode toStartSession(Session session) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("applicationId", session.getApplicationId());
        response.put("sessionId", session.getSessionId());
        response.put("arn", session.getArn());
        return response;
    }

    public ObjectNode toSessionEnvelope(Session session) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("session", toSession(session));
        return response;
    }

    public ObjectNode toListSessions(List<Session> items) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("sessions");
        for (Session session : items) {
            list.add(toSessionSummary(session));
        }
        return response;
    }

    public ObjectNode toTerminateSession(Session session) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("applicationId", session.getApplicationId());
        response.put("sessionId", session.getSessionId());
        return response;
    }

    public ObjectNode toCreateResponse(Application application) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("applicationId", application.getApplicationId());
        if (application.getName() != null) {
            response.put("name", application.getName());
        }
        response.put("arn", application.getArn());
        return response;
    }

    public ObjectNode toApplicationEnvelope(Application application) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("application", toApplication(application));
        return response;
    }

    public ObjectNode toListApplications(List<Application> items) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("applications");
        for (Application application : items) {
            list.add(toSummary(application));
        }
        return response;
    }

    public ObjectNode toApplication(Application application) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("applicationId", application.getApplicationId());
        if (application.getName() != null) {
            node.put("name", application.getName());
        }
        node.put("arn", application.getArn());
        node.put("releaseLabel", application.getReleaseLabel());
        node.put("type", application.getType());
        node.put("state", application.getState());
        if (application.getStateDetails() != null) {
            node.put("stateDetails", application.getStateDetails());
        }
        node.put("createdAt", application.getCreatedAt());
        node.put("updatedAt", application.getUpdatedAt());
        if (application.getArchitecture() != null) {
            node.put("architecture", application.getArchitecture());
        }
        ObjectNode autoStart = node.putObject("autoStartConfiguration");
        autoStart.put("enabled", Boolean.TRUE.equals(application.getAutoStartEnabled()));
        ObjectNode autoStop = node.putObject("autoStopConfiguration");
        autoStop.put("enabled", Boolean.TRUE.equals(application.getAutoStopEnabled()));
        if (application.getIdleTimeoutMinutes() != null) {
            autoStop.put("idleTimeoutMinutes", application.getIdleTimeoutMinutes());
        }
        if (application.getInitialCapacity() != null) {
            node.set("initialCapacity", objectMapper.valueToTree(application.getInitialCapacity()));
        }
        if (application.getMaximumCapacity() != null) {
            node.set("maximumCapacity", objectMapper.valueToTree(application.getMaximumCapacity()));
        }
        if (application.getNetworkConfiguration() != null) {
            node.set("networkConfiguration", objectMapper.valueToTree(application.getNetworkConfiguration()));
        }
        if (application.getInteractiveConfiguration() != null) {
            node.set("interactiveConfiguration",
                    objectMapper.valueToTree(application.getInteractiveConfiguration()));
        }
        ObjectNode tags = node.putObject("tags");
        if (application.getTags() != null) {
            application.getTags().forEach(tags::put);
        }
        return node;
    }

    private ObjectNode toSummary(Application application) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", application.getApplicationId());
        if (application.getName() != null) {
            node.put("name", application.getName());
        }
        node.put("arn", application.getArn());
        node.put("releaseLabel", application.getReleaseLabel());
        node.put("type", application.getType());
        node.put("state", application.getState());
        if (application.getStateDetails() != null) {
            node.put("stateDetails", application.getStateDetails());
        }
        node.put("createdAt", application.getCreatedAt());
        node.put("updatedAt", application.getUpdatedAt());
        if (application.getArchitecture() != null) {
            node.put("architecture", application.getArchitecture());
        }
        return node;
    }

    @Override
    public String serviceKey() {
        return SERVICE;
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        Application application = requireByArn(region, arn);
        return Map.copyOf(application.getTags() == null ? Map.of() : application.getTags());
    }

    @Override
    public synchronized void tagResource(String region, String arn, Map<String, String> tags) {
        Application application = requireByArn(region, arn);
        Map<String, String> current = new LinkedHashMap<>(
                application.getTags() == null ? Map.of() : application.getTags());
        if (tags != null) {
            current.putAll(tags);
        }
        application.setTags(current);
        application.setUpdatedAt(Instant.now().getEpochSecond());
        applications.put(applicationKey(region, application.getApplicationId()), application);
    }

    @Override
    public synchronized void untagResource(String region, String arn, List<String> tagKeys) {
        Application application = requireByArn(region, arn);
        Map<String, String> current = new LinkedHashMap<>(
                application.getTags() == null ? Map.of() : application.getTags());
        if (tagKeys != null) {
            tagKeys.forEach(current::remove);
        }
        application.setTags(current);
        application.setUpdatedAt(Instant.now().getEpochSecond());
        applications.put(applicationKey(region, application.getApplicationId()), application);
    }

    private Application requireById(String region, String applicationId) {
        if (applicationId == null || applicationId.isBlank()) {
            throw validation("applicationId is required.");
        }
        Application application = applications.get(applicationKey(region, applicationId)).orElse(null);
        if (application == null) {
            throw notFound(applicationId);
        }
        return application;
    }

    private Application requireByArn(String region, String arn) {
        return requireById(region, applicationIdFromArn(arn));
    }

    private Application findByName(String region, String name) {
        for (Application application : applications.scan(key -> key.startsWith(region + "::"))) {
            if (name.equals(application.getName())) {
                return application;
            }
        }
        return null;
    }

    private Application findByClientToken(String region, String clientToken) {
        for (Application application : applications.scan(key -> key.startsWith(region + "::"))) {
            if (clientToken.equals(application.getClientToken())) {
                return application;
            }
        }
        return null;
    }

    private static void requireMutable(Application application, String action) {
        if (!MUTABLE_STATES.contains(application.getState())) {
            throw validation("Application " + application.getApplicationId()
                    + " must be in CREATED or STOPPED state to be " + action + ".");
        }
    }

    private void applyAutoStart(Application application, JsonNode node, boolean applyDefaults) {
        if (node == null || node.isNull()) {
            if (applyDefaults) {
                application.setAutoStartEnabled(true);
            }
            return;
        }
        if (node.has("enabled")) {
            application.setAutoStartEnabled(node.get("enabled").asBoolean());
        } else if (applyDefaults) {
            application.setAutoStartEnabled(true);
        }
    }

    private void applyAutoStop(Application application, JsonNode node, boolean applyDefaults) {
        if (node == null || node.isNull()) {
            if (applyDefaults) {
                application.setAutoStopEnabled(true);
                application.setIdleTimeoutMinutes(DEFAULT_IDLE_TIMEOUT_MINUTES);
            }
            return;
        }
        if (node.has("enabled")) {
            application.setAutoStopEnabled(node.get("enabled").asBoolean());
        } else if (applyDefaults) {
            application.setAutoStopEnabled(true);
        }
        if (node.has("idleTimeoutMinutes")) {
            application.setIdleTimeoutMinutes(node.get("idleTimeoutMinutes").asInt());
        } else if (applyDefaults && application.getIdleTimeoutMinutes() == null) {
            application.setIdleTimeoutMinutes(DEFAULT_IDLE_TIMEOUT_MINUTES);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asObjectMap(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject()) {
            return null;
        }
        return objectMapper.convertValue(node, Map.class);
    }

    private static Map<String, String> readTags(JsonNode tagsNode) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (tagsNode == null || tagsNode.isNull()) {
            return tags;
        }
        if (!tagsNode.isObject()) {
            throw validation("tags must be an object.");
        }
        tagsNode.fields().forEachRemaining(entry -> {
            if (!entry.getValue().isTextual()) {
                throw validation("tags values must be strings.");
            }
            tags.put(entry.getKey(), entry.getValue().textValue());
        });
        return tags;
    }

    static String applicationArn(String region, String account, String applicationId) {
        return "arn:aws:emr-serverless:" + region + ":" + account + ":/applications/" + applicationId;
    }

    static String applicationIdFromArn(String arn) {
        if (arn == null || arn.isBlank()) {
            throw validation("resourceArn is required.");
        }
        int marker = arn.lastIndexOf("/applications/");
        if (marker < 0) {
            throw notFound(arn);
        }
        String applicationId = arn.substring(marker + "/applications/".length());
        if (applicationId.isBlank()) {
            throw notFound(arn);
        }
        return applicationId;
    }

    private static String applicationKey(String region, String applicationId) {
        return region + "::" + applicationId;
    }

    private static String jobRunKey(String region, String applicationId, String jobRunId) {
        return region + "::" + applicationId + "::jobrun::" + jobRunId;
    }

    private static String jobRunPrefix(String region, String applicationId) {
        return region + "::" + applicationId + "::jobrun::";
    }

    private static String sessionKey(String region, String applicationId, String sessionId) {
        return region + "::" + applicationId + "::session::" + sessionId;
    }

    private static String sessionPrefix(String region, String applicationId) {
        return region + "::" + applicationId + "::session::";
    }

    static String jobRunArn(String region, String account, String applicationId, String jobRunId) {
        return applicationArn(region, account, applicationId) + "/jobruns/" + jobRunId;
    }

    static String sessionArn(String region, String account, String applicationId, String sessionId) {
        return applicationArn(region, account, applicationId) + "/sessions/" + sessionId;
    }

    private JobRun requireJobRun(String region, String applicationId, String jobRunId) {
        if (jobRunId == null || jobRunId.isBlank()) {
            throw validation("jobRunId is required.");
        }
        JobRun jobRun = jobRuns.get(jobRunKey(region, applicationId, jobRunId)).orElse(null);
        if (jobRun == null) {
            throw new AwsException(
                    "ResourceNotFoundException",
                    "Job run " + jobRunId + " does not exist.",
                    404);
        }
        return jobRun;
    }

    private Session requireSession(String region, String applicationId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw validation("sessionId is required.");
        }
        Session session = sessions.get(sessionKey(region, applicationId, sessionId)).orElse(null);
        if (session == null) {
            throw new AwsException(
                    "ResourceNotFoundException",
                    "Session " + sessionId + " does not exist.",
                    404);
        }
        return session;
    }

    private JobRun findJobRunByClientToken(String region, String applicationId, String clientToken) {
        for (JobRun jobRun : jobRuns.scan(key -> key.startsWith(jobRunPrefix(region, applicationId)))) {
            if (clientToken.equals(jobRun.getClientToken())) {
                return jobRun;
            }
        }
        return null;
    }

    private static boolean interactiveEnabled(Application application) {
        Map<String, Object> config = application.getInteractiveConfiguration();
        if (config == null || config.isEmpty()) {
            return false;
        }
        return isTrue(config.get("studioEnabled"))
                || isTrue(config.get("livyEndpointEnabled"))
                || isTrue(config.get("sessionEnabled"));
    }

    private static boolean isTrue(Object value) {
        return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value));
    }

    private ObjectNode toJobRun(JobRun jobRun) {
        ObjectNode node = toJobRunSummary(jobRun);
        node.put("jobRunId", jobRun.getJobRunId());
        if (jobRun.getJobDriver() != null && !jobRun.getJobDriver().isEmpty()) {
            node.set("jobDriver", objectMapper.valueToTree(jobRun.getJobDriver()));
        }
        if (jobRun.getExecutionTimeoutMinutes() != null) {
            node.put("executionTimeoutMinutes", jobRun.getExecutionTimeoutMinutes());
        }
        ObjectNode tags = node.putObject("tags");
        if (jobRun.getTags() != null) {
            jobRun.getTags().forEach(tags::put);
        }
        return node;
    }

    private ObjectNode toJobRunSummary(JobRun jobRun) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("applicationId", jobRun.getApplicationId());
        node.put("id", jobRun.getJobRunId());
        if (jobRun.getName() != null) {
            node.put("name", jobRun.getName());
        }
        node.put("arn", jobRun.getArn());
        node.put("createdBy", jobRun.getCreatedBy());
        node.put("createdAt", jobRun.getCreatedAt());
        node.put("updatedAt", jobRun.getUpdatedAt());
        node.put("executionRole", jobRun.getExecutionRole());
        node.put("state", jobRun.getState());
        node.put("stateDetails", jobRun.getStateDetails() == null ? "" : jobRun.getStateDetails());
        node.put("releaseLabel", jobRun.getReleaseLabel());
        node.put("attempt", jobRun.getAttempt());
        return node;
    }

    private ObjectNode toJobRunAttempt(JobRun jobRun) {
        ObjectNode node = toJobRunSummary(jobRun);
        node.put("jobCreatedAt", jobRun.getCreatedAt());
        return node;
    }

    private ObjectNode toSession(Session session) {
        ObjectNode node = toSessionSummary(session);
        ObjectNode tags = node.putObject("tags");
        if (session.getTags() != null) {
            session.getTags().forEach(tags::put);
        }
        return node;
    }

    private ObjectNode toSessionSummary(Session session) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("applicationId", session.getApplicationId());
        node.put("sessionId", session.getSessionId());
        node.put("arn", session.getArn());
        if (session.getName() != null) {
            node.put("name", session.getName());
        }
        node.put("state", session.getState());
        node.put("stateDetails", session.getStateDetails() == null ? "" : session.getStateDetails());
        node.put("releaseLabel", session.getReleaseLabel());
        node.put("executionRoleArn", session.getExecutionRoleArn());
        node.put("createdBy", session.getCreatedBy());
        node.put("createdAt", session.getCreatedAt());
        node.put("updatedAt", session.getUpdatedAt());
        return node;
    }

    private static String newApplicationId() {
        return newEmrId();
    }

    private static String newEmrId() {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        return "00" + HexFormat.of().formatHex(bytes).substring(2);
    }

    private static String echoType(String type) {
        if (type == null || type.isBlank()) {
            return "Spark";
        }
        if ("SPARK".equalsIgnoreCase(type)) {
            return "Spark";
        }
        if ("HIVE".equalsIgnoreCase(type)) {
            return "Hive";
        }
        return type;
    }

    private static void requireObject(JsonNode request, String field) {
        if (request == null || !request.isObject()) {
            throw validation(field + " must be a JSON object.");
        }
    }

    private static String requireText(JsonNode request, String field) {
        String value = optionalText(request, field);
        if (value == null) {
            throw validation(field + " is required.");
        }
        return value;
    }

    private static String optionalText(JsonNode request, String field) {
        if (request == null || !request.hasNonNull(field)) {
            return null;
        }
        JsonNode node = request.get(field);
        if (!node.isTextual()) {
            throw validation(field + " must be a string.");
        }
        String value = node.textValue();
        return value == null || value.isBlank() ? null : value;
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    private static AwsException notFound(String applicationId) {
        return new AwsException(
                "ResourceNotFoundException",
                "Application " + applicationId + " does not exist.",
                404);
    }

    private static AwsException conflict(String name, String message) {
        return new AwsException("ConflictException", message, 409);
    }
}
