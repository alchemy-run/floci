package io.github.hectorvent.floci.services.kinesisanalytics;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.kinesisanalytics.container.FlinkContainerManager;
import io.github.hectorvent.floci.services.kinesisanalytics.model.ApplicationStatus;
import io.github.hectorvent.floci.services.kinesisanalytics.model.FlinkApplication;
import io.github.hectorvent.floci.services.kinesisanalytics.model.Snapshot;
import io.github.hectorvent.floci.services.kinesisanalytics.model.SnapshotStatus;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Control plane for Managed Service for Apache Flink (Kinesis Analytics V2). Holds the
 * application state machine and delegates the real Flink cluster to {@link FlinkContainerManager}.
 *
 * <p>Modelled on {@code AmazonMqService}: {@code CreateApplication} lands in {@code READY} with no
 * container (AWS-faithful — a created application is not running); {@code StartApplication} spins up
 * a Flink JobManager container and the readiness poller flips {@code STARTING → RUNNING} once the
 * JobManager REST API answers; {@code StopApplication} tears the container down and returns to
 * {@code READY}.
 */
@ApplicationScoped
public class KinesisAnalyticsV2Service {

    private static final Logger LOG = Logger.getLogger(KinesisAnalyticsV2Service.class);

    // AWS: ApplicationName Length Constraints: 1-128, Pattern: [a-zA-Z0-9_.-]+
    private static final Pattern APPLICATION_NAME = Pattern.compile("[a-zA-Z0-9_.-]{1,128}");

    // AWS: "the maximum number of user-defined application tags is 50" (the stated 200-tag ceiling
    // on the Tags/TagKeys shapes includes AWS-managed system tags, which floci does not model).
    private static final int MAX_USER_TAGS = 50;

    // CreateApplicationPresignedUrl's SessionExpirationDurationInSeconds valid range (30 min - 12 hr).
    private static final long MIN_SESSION_EXPIRATION_SECONDS = 1800;
    private static final long MAX_SESSION_EXPIRATION_SECONDS = 43200;

    private final StorageBackend<String, FlinkApplication> storage;
    private final EmulatorConfig config;
    private final RegionResolver regionResolver;
    private final FlinkContainerManager containerManager;
    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor();

    @Inject
    public KinesisAnalyticsV2Service(StorageFactory storageFactory, EmulatorConfig config,
                                     RegionResolver regionResolver,
                                     FlinkContainerManager containerManager) {
        this.storage = storageFactory.create("kinesisanalytics", "kinesisanalytics-applications.json",
                new TypeReference<Map<String, FlinkApplication>>() {});
        this.config = config;
        this.regionResolver = regionResolver;
        this.containerManager = containerManager;
    }

    @PostConstruct
    public void init() {
        startReadinessPoller();
    }

    @PreDestroy
    public void shutdown() {
        // Container teardown is wired into EmulatorLifecycle.onStop() via
        // FlinkContainerManager.stopAll() (ordered with the other container managers);
        // here we only stop the readiness poller. Wait briefly for an in-flight tick so a
        // mid-flight putApplication cannot race the storage flush in onStop().
        poller.shutdown();
        try {
            if (!poller.awaitTermination(5, TimeUnit.SECONDS)) {
                poller.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            poller.shutdownNow();
        }
    }

    public FlinkApplication createApplication(String applicationName, String runtimeEnvironment,
                                              String serviceExecutionRole, String applicationDescription,
                                              String applicationMode) {
        return createApplication(applicationName, runtimeEnvironment, serviceExecutionRole,
                applicationDescription, applicationMode, null, null, null, 1);
    }

    public FlinkApplication createApplication(String applicationName, String runtimeEnvironment,
                                              String serviceExecutionRole, String applicationDescription,
                                              String applicationMode, String codeS3Bucket, String codeS3Key,
                                              String codeS3ObjectVersion, int parallelism) {
        return createApplication(applicationName, runtimeEnvironment, serviceExecutionRole,
                applicationDescription, applicationMode, codeS3Bucket, codeS3Key, codeS3ObjectVersion,
                parallelism, null);
    }

    public FlinkApplication createApplication(String applicationName, String runtimeEnvironment,
                                              String serviceExecutionRole, String applicationDescription,
                                              String applicationMode, String codeS3Bucket, String codeS3Key,
                                              String codeS3ObjectVersion, int parallelism,
                                              Map<String, String> tags) {
        return createApplication(applicationName, runtimeEnvironment, serviceExecutionRole,
                applicationDescription, applicationMode, codeS3Bucket, codeS3Key, codeS3ObjectVersion,
                parallelism, tags, null);
    }

    public FlinkApplication createApplication(String applicationName, String runtimeEnvironment,
                                              String serviceExecutionRole, String applicationDescription,
                                              String applicationMode, String codeS3Bucket, String codeS3Key,
                                              String codeS3ObjectVersion, int parallelism,
                                              Map<String, String> tags,
                                              Map<String, Map<String, String>> environmentProperties) {
        return createApplication(applicationName, runtimeEnvironment, serviceExecutionRole,
                applicationDescription, applicationMode, codeS3Bucket, codeS3Key, codeS3ObjectVersion,
                parallelism, tags, environmentProperties, null);
    }

    public FlinkApplication createApplication(String applicationName, String runtimeEnvironment,
                                              String serviceExecutionRole, String applicationDescription,
                                              String applicationMode, String codeS3Bucket, String codeS3Key,
                                              String codeS3ObjectVersion, int parallelism,
                                              Map<String, String> tags,
                                              Map<String, Map<String, String>> environmentProperties,
                                              Boolean snapshotsEnabled) {
        return createApplication(applicationName, runtimeEnvironment, serviceExecutionRole,
                applicationDescription, applicationMode, codeS3Bucket, codeS3Key, codeS3ObjectVersion,
                parallelism, tags, environmentProperties, snapshotsEnabled, null, List.of());
    }

    public synchronized FlinkApplication createApplication(String applicationName, String runtimeEnvironment,
                                              String serviceExecutionRole, String applicationDescription,
                                              String applicationMode, String codeS3Bucket, String codeS3Key,
                                              String codeS3ObjectVersion, int parallelism, Map<String, String> tags,
                                              Map<String, Map<String, String>> environmentProperties,
                                              Boolean snapshotsEnabled, JsonNode flinkConfiguration,
                                              List<String> loggingStreams) {
        ObjectNode configuration = mergeFlinkConfiguration(null, flinkConfiguration, false);
        validateLoggingStreams(loggingStreams);
        if (applicationMode != null && !"STREAMING".equals(applicationMode)) {
            throw new AwsException("InvalidArgumentException", "Only STREAMING applications are supported", 400);
        }
        if (applicationName == null || applicationName.isBlank()) {
            throw new AwsException("InvalidArgumentException", "ApplicationName is required", 400);
        }
        if (!APPLICATION_NAME.matcher(applicationName).matches()) {
            throw new AwsException("InvalidArgumentException",
                    "ApplicationName '" + applicationName
                            + "' does not match the required pattern [a-zA-Z0-9_.-]{1,128}", 400);
        }
        if (runtimeEnvironment == null || runtimeEnvironment.isBlank()) {
            throw new AwsException("InvalidArgumentException", "RuntimeEnvironment is required", 400);
        }
        // Reject a runtime we cannot back with a Flink image up front (AWS-faithful
        // InvalidArgumentException), rather than failing later at StartApplication.
        KinesisAnalyticsRuntimes.validate(runtimeEnvironment);
        if (serviceExecutionRole == null || serviceExecutionRole.isBlank()) {
            throw new AwsException("InvalidArgumentException", "ServiceExecutionRole is required", 400);
        }
        if (findApplication(applicationName).isPresent()) {
            throw new AwsException("ResourceInUseException",
                    "Application already exists: " + applicationName, 400);
        }
        if (tags != null && tags.size() > MAX_USER_TAGS) {
            throw new AwsException("TooManyTagsException",
                    "The maximum number of user-defined application tags is " + MAX_USER_TAGS, 400);
        }

        String accountId = regionResolver.getAccountId();
        String arn = AwsArnUtils.Arn.of("kinesisanalytics", regionResolver.getRegion(), accountId,
                "application/" + applicationName).toString();
        String mode = (applicationMode == null || applicationMode.isBlank()) ? "STREAMING" : applicationMode;

        FlinkApplication app = new FlinkApplication(applicationName, arn, runtimeEnvironment,
                serviceExecutionRole, mode);
        app.setAccountId(accountId);
        app.setApplicationDescription(applicationDescription);
        // AWS-faithful: a freshly created application is READY (not RUNNING); no container yet.
        app.setApplicationStatus(ApplicationStatus.READY);
        app.setCodeS3Bucket(codeS3Bucket);
        app.setCodeS3Key(codeS3Key);
        app.setCodeS3ObjectVersion(codeS3ObjectVersion);
        if (parallelism < 1) {
            throw new AwsException("InvalidArgumentException", "Parallelism must be positive", 400);
        }
        app.setParallelism("DEFAULT".equals(configuration.path("ParallelismConfiguration")
                .path("ConfigurationType").asText()) ? 1 : parallelism);
        if (tags != null) {
            app.setTags(new LinkedHashMap<>(tags));
        }
        if (environmentProperties != null) {
            app.setEnvironmentProperties(new LinkedHashMap<>(environmentProperties));
        }
        if (snapshotsEnabled != null) {
            app.setSnapshotsEnabled(snapshotsEnabled);
        }

        app.setFlinkConfiguration(configuration);
        loggingStreams.forEach(stream -> app.getCloudWatchLoggingOptions().put(UUID.randomUUID().toString(), stream));
        app.setConditionalToken(UUID.randomUUID().toString());
        rememberVersion(app);
        putApplication(app);
        LOG.infov("Created Kinesis Analytics V2 application {0}", applicationName);
        return app;
    }

    public FlinkApplication describeApplication(String applicationName) {
        if (applicationName == null || applicationName.isBlank()) {
            throw new AwsException("InvalidArgumentException", "ApplicationName is required", 400);
        }
        return findApplication(applicationName)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Application not found: " + applicationName, 400));
    }

    private Optional<FlinkApplication> findApplication(String name) {
        String arn = regionResolver.buildArn("kinesisanalytics", regionResolver.getRegion(), "application/" + name);
        if (storage instanceof AccountAwareStorageBackend<FlinkApplication> aware) {
            return aware.getForAccountMigratingLegacyKeys(regionResolver.getAccountId(), arn, List.of(name),
                    app -> arn.equals(app.getApplicationArn()));
        }
        Optional<FlinkApplication> found = storage.get(arn).filter(app -> arn.equals(app.getApplicationArn()));
        if (found.isEmpty()) {
            found = storage.get(name).filter(app -> arn.equals(app.getApplicationArn()));
            found.ifPresent(app -> {
                storage.put(arn, app);
                storage.delete(name);
            });
        }
        return found;
    }

    public List<FlinkApplication> listApplications() {
        return storage.scan(k -> true).stream()
                .filter(app -> app.getApplicationArn().equals(regionResolver.buildArn("kinesisanalytics",
                        regionResolver.getRegion(), "application/" + app.getApplicationName())))
                .distinct().sorted(Comparator.comparing(FlinkApplication::getApplicationName)).toList();
    }

    public synchronized FlinkApplication startApplication(String applicationName) {
        FlinkApplication app = describeApplication(applicationName);
        // Defends state persisted by a floci build older than the ApplicationName check in
        // createApplication: applicationARN is baked as literal text into the CloudWatch-log-format
        // config FlinkContainerManager writes on startup, and a name outside AWS's charset (most
        // notably '$', which can't be escaped to a safe literal there -- see
        // FlinkContainerManager#literalForLog4j2Pattern) either gets dropped from every emitted
        // applicationARN (breaking correlation with the real ApplicationARN) or, if ever un-dropped,
        // risks leaking an environment variable/system property via a log4j2 Lookup. Failing loudly
        // here beats either outcome, and real AWS could never have had this state to begin with.
        if (!APPLICATION_NAME.matcher(applicationName).matches()) {
            throw new AwsException("InvalidArgumentException",
                    "ApplicationName '" + applicationName + "' does not match the required pattern "
                            + "[a-zA-Z0-9_.-]{1,128}; this application predates that validation and "
                            + "must be deleted and recreated with a valid name", 400);
        }
        if (app.getApplicationStatus() != ApplicationStatus.READY) {
            throw new AwsException("ResourceInUseException",
                    "Application " + applicationName + " cannot be started while in state "
                            + app.getApplicationStatus() + "; it must be READY", 400);
        }

        if (config.services().kinesisAnalytics().mock()) {
            // No backing container: come up immediately.
            app.setApplicationStatus(ApplicationStatus.RUNNING);
        } else {
            try {
                // Start the container; the application stays STARTING until the readiness poller
                // observes the JobManager REST API answering.
                containerManager.startCluster(app);
                app.setApplicationStatus(ApplicationStatus.STARTING);
            } catch (AwsException e) {
                // Already an AWS-shaped client error (e.g. a missing/empty/unreadable application-code
                // S3 object from readJar); preserve its error code and message rather than masking it
                // as a 500.
                throw e;
            } catch (RuntimeException e) {
                // Unexpected provisioning failure (e.g. Docker unavailable). Keep the cause in the logs;
                // don't leak internal details into the AWS envelope. "InternalFailure" (no "Exception"
                // suffix, HTTP 500) is AWS's documented generic error common to every service's API
                // (e.g. https://docs.aws.amazon.com/comprehend/latest/APIReference/CommonErrors.html) —
                // it isn't declared per-operation in kinesisanalyticsv2's model (no operation here
                // declares any 5xx shape at all), so this isn't a code this specific API's model dictates,
                // it's the AWS-wide platform fallback, same as JsonErrorResponseUtils' own default.
                LOG.errorv(e, "Failed to start application {0}", applicationName);
                throw new AwsException("InternalFailure",
                        "Failed to start application " + applicationName, 500);
            }
        }
        app.setLastUpdateTimestamp(Instant.now());
        putApplication(app);
        LOG.infov("Starting Kinesis Analytics V2 application {0}", applicationName);
        return app;
    }

    public synchronized FlinkApplication stopApplication(String applicationName) {
        FlinkApplication app = describeApplication(applicationName);
        if (app.getApplicationStatus() != ApplicationStatus.RUNNING
                && app.getApplicationStatus() != ApplicationStatus.STARTING) {
            throw new AwsException("ResourceInUseException",
                    "Application " + applicationName + " cannot be stopped while in state "
                            + app.getApplicationStatus() + "; it must be RUNNING or STARTING", 400);
        }
        if (!config.services().kinesisAnalytics().mock()) {
            containerManager.stopCluster(app);
        }
        // Stopping the JobManager is synchronous, so the application returns to READY directly.
        app.setApplicationStatus(ApplicationStatus.READY);
        app.setLastUpdateTimestamp(Instant.now());
        app.getOperations().values().stream()
                .filter(info -> "IN_PROGRESS".equals(info.path("OperationStatus").asText()))
                .forEach(info -> {
                    info.put("OperationStatus", "CANCELLED");
                    info.put("EndTime", app.getLastUpdateTimestamp().toEpochMilli() / 1000.0);
                });
        putApplication(app);
        LOG.infov("Stopped Kinesis Analytics V2 application {0}", applicationName);
        return app;
    }

    public FlinkApplication updateApplication(String applicationName, Long currentApplicationVersionId,
                                              String serviceExecutionRole) {
        return updateApplication(applicationName, currentApplicationVersionId, serviceExecutionRole,
                null, null, null, null);
    }

    public FlinkApplication updateApplication(String applicationName, Long currentApplicationVersionId,
                                              String serviceExecutionRole, String codeS3Bucket,
                                              String codeS3Key, String codeS3ObjectVersion,
                                              Integer parallelism) {
        return updateApplication(applicationName, currentApplicationVersionId, serviceExecutionRole,
                codeS3Bucket, codeS3Key, codeS3ObjectVersion, parallelism, null);
    }

    public FlinkApplication updateApplication(String applicationName, Long currentApplicationVersionId,
                                              String serviceExecutionRole, String codeS3Bucket,
                                              String codeS3Key, String codeS3ObjectVersion,
                                              Integer parallelism, Boolean snapshotsEnabled) {
        return updateApplication(applicationName, currentApplicationVersionId, serviceExecutionRole,
                codeS3Bucket, codeS3Key, codeS3ObjectVersion, parallelism, snapshotsEnabled,
                null, null, null, null, Map.of());
    }

    public synchronized FlinkApplication updateApplication(String applicationName, Long currentApplicationVersionId,
                                              String serviceExecutionRole, String codeS3Bucket,
                                              String codeS3Key, String codeS3ObjectVersion,
                                              Integer parallelism, Boolean snapshotsEnabled,
                                              Map<String, Map<String, String>> environmentProperties,
                                              JsonNode flinkConfiguration, String runtimeEnvironment,
                                              String conditionalToken, Map<String, String> loggingUpdates) {
        FlinkApplication app = describeApplication(applicationName).mutableCopy();
        checkVersion(app, currentApplicationVersionId, conditionalToken);
        ensureConfigurable(app);
        if (app.getApplicationStatus() == ApplicationStatus.RUNNING
                && (environmentProperties != null || runtimeEnvironment != null || parallelism != null
                    || (flinkConfiguration != null && !flinkConfiguration.isMissingNode()))) {
            throw new AwsException("InvalidRequestException",
                    "Stop the application before changing runtime, environment or Flink configuration", 400);
        }
        rememberVersion(app);
        if (runtimeEnvironment != null) {
            KinesisAnalyticsRuntimes.validate(runtimeEnvironment);
            app.setRuntimeEnvironment(runtimeEnvironment);
        }
        ObjectNode configuration = mergeFlinkConfiguration(app.getFlinkConfiguration(), flinkConfiguration, true);
        validateLoggingStreams(new ArrayList<>(loggingUpdates.values()));
        loggingUpdates.forEach((id, stream) -> {
            if (!app.getCloudWatchLoggingOptions().containsKey(id)) {
                throw new AwsException("ResourceNotFoundException", "Logging option not found: " + id, 400);
            }
            app.getCloudWatchLoggingOptions().put(id, stream);
        });
        if (environmentProperties != null) {
            app.setEnvironmentProperties(environmentProperties);
        }
        app.setFlinkConfiguration(configuration);
        if (serviceExecutionRole != null && !serviceExecutionRole.isBlank()) {
            app.setServiceExecutionRole(serviceExecutionRole);
        }
        boolean codeChanged = codeS3Bucket != null || codeS3Key != null || codeS3ObjectVersion != null;
        if (codeChanged) {
            if (codeS3Bucket != null) {
                app.setCodeS3Bucket(codeS3Bucket);
            }
            if (codeS3Key != null) {
                app.setCodeS3Key(codeS3Key);
            }
            app.setCodeS3ObjectVersion(codeS3ObjectVersion);
            if (!app.hasCode()) {
                throw new AwsException("InvalidArgumentException", "Code requires an S3 bucket and file key", 400);
            }
        }
        if (parallelism != null) {
            if (parallelism < 1) {
                throw new AwsException("InvalidArgumentException", "Parallelism must be positive", 400);
            }
            app.setParallelism(parallelism);
        }
        if ("DEFAULT".equals(configuration.path("ParallelismConfiguration").path("ConfigurationType").asText())) {
            app.setParallelism(1);
        }
        if (snapshotsEnabled != null) {
            app.setSnapshotsEnabled(snapshotsEnabled);
        }

        if (codeChanged && app.getApplicationStatus() == ApplicationStatus.RUNNING
                && !config.services().kinesisAnalytics().mock()) {
            if (app.getTaskManagerContainerId() == null) {
                // The application is running as a bare cluster (no code yet); attaching code to an
                // already-running cluster for the first time is not yet emulated.
                throw new AwsException("InvalidRequestException",
                        "Adding code to a running bare-cluster application is not supported; "
                                + "stop and start " + applicationName + " instead", 400);
            }
            try {
                // In-place redeploy: cancel the current job and swap in the new JAR without tearing
                // down the JobManager/TaskManager containers. The readiness poller resubmits it, the
                // same way it submits a fresh job on StartApplication.
                containerManager.redeployCode(app);
                app.setApplicationStatus(ApplicationStatus.STARTING);
            } catch (AwsException e) {
                throw e;
            } catch (RuntimeException e) {
                // Same AWS-wide "InternalFailure" generic 500 as startApplication's catch above.
                LOG.errorv(e, "Failed to redeploy code for application {0}", applicationName);
                throw new AwsException("InternalFailure",
                        "Failed to redeploy application code for " + applicationName, 500);
            }
        }

        completeConfigurationChange(app, "UpdateApplication");
        LOG.infov("Updated Kinesis Analytics V2 application {0} to version {1}",
                applicationName, app.getApplicationVersionId());
        return app;
    }

    public synchronized void deleteApplication(String applicationName, Instant createTimestamp) {
        FlinkApplication app = describeApplication(applicationName);
        // AWS requires CreateTimestamp and rejects a value that does not match the stored one. The
        // wire value is epoch seconds, so compare at second granularity.
        if (createTimestamp == null) {
            throw new AwsException("InvalidArgumentException", "CreateTimestamp is required", 400);
        }
        if (app.getCreateTimestamp() == null
                || app.getCreateTimestamp().getEpochSecond() != createTimestamp.getEpochSecond()) {
            throw new AwsException("InvalidArgumentException",
                    "Provided CreateTimestamp does not match application " + applicationName, 400);
        }
        // AWS rejects deletion of a non-stopped application (RUNNING/STARTING) with
        // ResourceInUseException; the caller must StopApplication first.
        if (app.getApplicationStatus() != ApplicationStatus.READY) {
            throw new AwsException("ResourceInUseException",
                    "Application " + applicationName + " cannot be deleted while in state "
                            + app.getApplicationStatus() + "; stop the application first", 400);
        }
        if (!config.services().kinesisAnalytics().mock()) {
            // No-op for a READY app with no container; also clears any stale container left from a
            // previous run (containerId is not persisted across an emulator restart).
            containerManager.stopCluster(app);
            // Only on delete, never on a plain StopApplication (stopCluster above) — the savepoints
            // volume must survive a stop/restart cycle so snapshots remain describable/listable.
            containerManager.removeSavepointsVolume(app);
        }
        storage.delete(app.getApplicationArn());
        LOG.infov("Deleted Kinesis Analytics V2 application {0}", applicationName);
    }

    public Map<String, String> listTagsForResource(String resourceArn) {
        return findByArn(resourceArn).getTags();
    }

    public synchronized Map<String, String> tagResource(String resourceArn, Map<String, String> tags) {
        FlinkApplication app = findByArn(resourceArn);
        Map<String, String> merged = new LinkedHashMap<>(app.getTags());
        if (tags != null) {
            merged.putAll(tags);
        }
        if (merged.size() > MAX_USER_TAGS) {
            throw new AwsException("TooManyTagsException",
                    "The maximum number of user-defined application tags is " + MAX_USER_TAGS, 400);
        }
        app.setTags(merged);
        putApplication(app);
        return app.getTags();
    }

    public synchronized Map<String, String> untagResource(String resourceArn, List<String> tagKeys) {
        FlinkApplication app = findByArn(resourceArn);
        if (tagKeys != null) {
            tagKeys.forEach(app.getTags()::remove);
        }
        putApplication(app);
        return app.getTags();
    }

    public Snapshot createApplicationSnapshot(String applicationName, String snapshotName) {
        FlinkApplication app = describeApplication(applicationName);
        if (snapshotName == null || snapshotName.isBlank()) {
            throw new AwsException("InvalidArgumentException", "SnapshotName is required", 400);
        }
        // Real AWS requires a live, running job to snapshot — a bare cluster (no code) never has one.
        if (app.getApplicationStatus() != ApplicationStatus.RUNNING || !app.hasCode()) {
            throw new AwsException("InvalidRequestException",
                    "Application " + applicationName + " must be RUNNING with a deployed job to "
                            + "create a snapshot", 400);
        }
        if (!app.isSnapshotsEnabled()) {
            throw new AwsException("InvalidRequestException",
                    "Snapshots are not enabled for application " + applicationName, 400);
        }
        if (app.getSnapshots().containsKey(snapshotName)) {
            throw new AwsException("ResourceInUseException",
                    "Snapshot already exists: " + snapshotName, 400);
        }

        Snapshot snapshot = new Snapshot(snapshotName, app.getApplicationVersionId(), app.getRuntimeEnvironment());
        if (config.services().kinesisAnalytics().mock()) {
            // No backing job to snapshot: come up READY immediately, same as StartApplication's
            // mock-mode shortcut.
            snapshot.setSnapshotStatus(SnapshotStatus.READY);
        } else {
            try {
                containerManager.createSnapshot(app, snapshot);
            } catch (RuntimeException e) {
                LOG.errorv(e, "Failed to trigger snapshot {0} for application {1}",
                        snapshotName, applicationName);
                snapshot.setSnapshotStatus(SnapshotStatus.FAILED);
            }
        }
        app.getSnapshots().put(snapshotName, snapshot);
        putApplication(app);
        LOG.infov("Creating Kinesis Analytics V2 snapshot {0} for application {1}",
                snapshotName, applicationName);
        return snapshot;
    }

    public Snapshot describeApplicationSnapshot(String applicationName, String snapshotName) {
        FlinkApplication app = describeApplication(applicationName);
        Snapshot snapshot = app.getSnapshots().get(snapshotName);
        if (snapshot == null) {
            throw new AwsException("ResourceNotFoundException",
                    "Snapshot not found: " + snapshotName, 400);
        }
        return snapshot;
    }

    public List<Snapshot> listApplicationSnapshots(String applicationName) {
        return List.copyOf(describeApplication(applicationName).getSnapshots().values());
    }

    public void deleteApplicationSnapshot(String applicationName, String snapshotName,
                                          Instant snapshotCreationTimestamp) {
        FlinkApplication app = describeApplication(applicationName);
        Snapshot snapshot = app.getSnapshots().get(snapshotName);
        if (snapshot == null) {
            throw new AwsException("ResourceNotFoundException",
                    "Snapshot not found: " + snapshotName, 400);
        }
        if (snapshotCreationTimestamp == null || snapshot.getSnapshotCreationTimestamp() == null
                || snapshot.getSnapshotCreationTimestamp().getEpochSecond()
                        != snapshotCreationTimestamp.getEpochSecond()) {
            throw new AwsException("InvalidArgumentException",
                    "Provided SnapshotCreationTimestamp does not match snapshot " + snapshotName, 400);
        }
        if (snapshot.getSnapshotStatus() == SnapshotStatus.CREATING) {
            throw new AwsException("ResourceInUseException",
                    "Snapshot " + snapshotName + " cannot be deleted while still being created", 400);
        }
        if (!config.services().kinesisAnalytics().mock()) {
            containerManager.deleteSnapshotFiles(app, snapshot);
        }
        app.getSnapshots().remove(snapshotName);
        putApplication(app);
        LOG.infov("Deleted Kinesis Analytics V2 snapshot {0} for application {1}",
                snapshotName, applicationName);
    }

    /**
     * Real AWS returns a session-authorized URL to the application's Flink Dashboard (or, for a
     * Zeppelin Studio notebook, its UI — not supported here since floci already rejects Zeppelin
     * runtimes at CreateApplication). Since floci has no real IAM-backed session/proxy layer for this,
     * it returns the JobManager's own REST/dashboard URL directly rather than a genuinely time-limited,
     * signed one — the same "real behavior, stubbed authorization" tradeoff MWAA's CreateWebLoginToken
     * makes for its own SSO handshake.
     */
    public String createApplicationPresignedUrl(String applicationName, String urlType,
                                                 Long sessionExpirationDurationInSeconds) {
        FlinkApplication app = describeApplication(applicationName);
        if (!"FLINK_DASHBOARD_URL".equals(urlType)) {
            throw new AwsException("InvalidArgumentException",
                    "Unsupported UrlType: " + urlType + "; only FLINK_DASHBOARD_URL is supported", 400);
        }
        if (sessionExpirationDurationInSeconds != null
                && (sessionExpirationDurationInSeconds < MIN_SESSION_EXPIRATION_SECONDS
                        || sessionExpirationDurationInSeconds > MAX_SESSION_EXPIRATION_SECONDS)) {
            throw new AwsException("InvalidArgumentException",
                    "SessionExpirationDurationInSeconds must be between " + MIN_SESSION_EXPIRATION_SECONDS
                            + " and " + MAX_SESSION_EXPIRATION_SECONDS, 400);
        }
        if (app.getApplicationStatus() != ApplicationStatus.RUNNING || app.getRestEndpoint() == null) {
            throw new AwsException("ResourceInUseException",
                    "Application " + applicationName + " is not available for this operation", 400);
        }
        return app.getRestEndpoint();
    }

    public synchronized FlinkApplication updateMaintenance(String name, String startTime) {
        FlinkApplication app = describeApplication(name).mutableCopy();
        ensureConfigurable(app);
        if (startTime == null || !startTime.matches("(?:[01][0-9]|2[0-3]):[0-5][0-9]")) {
            throw new AwsException("InvalidArgumentException", "Maintenance window must be HH:mm UTC", 400);
        }
        app.setMaintenanceWindowStartTime(startTime);
        app.setLastUpdateTimestamp(Instant.now());
        putApplication(app);
        return app;
    }

    public synchronized FlinkApplication addLoggingOption(String name, Long version, String token, String stream) {
        FlinkApplication app = describeApplication(name).mutableCopy();
        checkVersion(app, version, token);
        ensureConfigurable(app);
        validateLoggingStreams(stream == null ? List.of() : List.of(stream));
        if (stream == null) {
            throw new AwsException("InvalidArgumentException", "LogStreamARN is required", 400);
        }
        if (!app.getCloudWatchLoggingOptions().isEmpty()) {
            throw new AwsException("InvalidArgumentException", "Only one CloudWatch logging option is supported", 400);
        }
        rememberVersion(app);
        app.getCloudWatchLoggingOptions().put(UUID.randomUUID().toString(), stream);
        completeConfigurationChange(app, "AddApplicationCloudWatchLoggingOption");
        return app;
    }

    public synchronized FlinkApplication deleteLoggingOption(String name, Long version, String token, String id) {
        FlinkApplication app = describeApplication(name).mutableCopy();
        checkVersion(app, version, token);
        ensureConfigurable(app);
        if (id == null || id.isBlank()) {
            throw new AwsException("InvalidArgumentException", "CloudWatchLoggingOptionId is required", 400);
        }
        if (!app.getCloudWatchLoggingOptions().containsKey(id)) {
            throw new AwsException("ResourceNotFoundException", "Logging option not found: " + id, 400);
        }
        rememberVersion(app);
        app.getCloudWatchLoggingOptions().remove(id);
        completeConfigurationChange(app, "DeleteApplicationCloudWatchLoggingOption");
        return app;
    }

    public synchronized List<FlinkApplication> listApplicationVersions(String name) {
        FlinkApplication app = describeApplication(name);
        Map<Long, FlinkApplication> versions = new LinkedHashMap<>(app.getVersions());
        versions.putIfAbsent(app.getApplicationVersionId(), app.versionSnapshot());
        return versions.values().stream()
                .sorted(Comparator.comparingLong(FlinkApplication::getApplicationVersionId).reversed())
                .map(FlinkApplication::versionSnapshot).toList();
    }

    public FlinkApplication describeApplicationVersion(String name, Long version) {
        if (version == null || version < 1) {
            throw new AwsException("InvalidArgumentException", "ApplicationVersionId must be positive", 400);
        }
        return listApplicationVersions(name).stream().filter(app -> app.getApplicationVersionId() == version)
                .findFirst().orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Application version not found: " + version, 400));
    }

    public synchronized List<ObjectNode> listApplicationOperations(String name, String operation, String status) {
        return describeApplication(name).getOperations().values().stream()
                .filter(info -> operation == null || operation.equals(info.path("Operation").asText()))
                .filter(info -> status == null || status.equals(info.path("OperationStatus").asText()))
                .sorted(Comparator.comparingDouble((ObjectNode info) -> info.path("StartTime").asDouble()).reversed())
                .map(ObjectNode::deepCopy).toList();
    }

    public ObjectNode describeApplicationOperation(String name, String id) {
        FlinkApplication app = describeApplication(name);
        if (id == null || id.isBlank()) {
            throw new AwsException("InvalidArgumentException", "OperationId is required", 400);
        }
        ObjectNode operation = app.getOperations().get(id);
        if (operation == null) {
            throw new AwsException("ResourceNotFoundException", "Application operation not found: " + id, 400);
        }
        return operation.deepCopy();
    }

    public void validateRestore(String name, JsonNode configuration) {
        describeApplication(name);
        String type = configuration.path("ApplicationRestoreType").asText("SKIP_RESTORE_FROM_SNAPSHOT");
        if ("SKIP_RESTORE_FROM_SNAPSHOT".equals(type)) {
            return;
        }
        if ("RESTORE_FROM_CUSTOM_SNAPSHOT".equals(type)) {
            describeApplicationSnapshot(name, configuration.path("SnapshotName").asText(null));
        } else if (!"RESTORE_FROM_LATEST_SNAPSHOT".equals(type)) {
            throw new AwsException("InvalidArgumentException", "Invalid ApplicationRestoreType", 400);
        }
        throw new AwsException("InvalidRequestException", "Snapshot restore is not implemented", 400);
    }

    public void rollbackApplication(String name, Long version) {
        FlinkApplication app = describeApplication(name);
        checkVersion(app, version, null);
        throw new AwsException("InvalidRequestException", "There is no supported in-progress operation to roll back", 400);
    }

    private void validateLoggingStreams(List<String> streams) {
        if (streams.size() > 1) {
            throw new AwsException("InvalidArgumentException", "Only one CloudWatch logging option is supported", 400);
        }
        String prefix = regionResolver.buildArn("logs", regionResolver.getRegion(), "log-group:");
        for (String stream : streams) {
            if (stream == null || !stream.startsWith(prefix) || !stream.contains(":log-stream:")
                    || stream.endsWith(":log-stream:")) {
                throw new AwsException("InvalidArgumentException", "LogStreamARN must identify a log stream in this account and region", 400);
            }
        }
    }

    private static void ensureConfigurable(FlinkApplication app) {
        if (app.getApplicationStatus() != ApplicationStatus.READY
                && app.getApplicationStatus() != ApplicationStatus.RUNNING) {
            throw new AwsException("ResourceInUseException", "Application is not available for configuration", 400);
        }
    }

    private static void checkVersion(FlinkApplication app, Long version, String token) {
        if ((version == null) == (token == null)) {
            throw new AwsException("InvalidArgumentException",
                    "Specify exactly one of CurrentApplicationVersionId or ConditionalToken", 400);
        }
        if ((version != null && version != app.getApplicationVersionId())
                || (token != null && !token.equals(app.getConditionalToken()))) {
            throw new AwsException("ConcurrentModificationException", "Application version or conditional token is stale", 400);
        }
    }

    private static void rememberVersion(FlinkApplication app) {
        app.getVersions().putIfAbsent(app.getApplicationVersionId(), app.versionSnapshot());
    }

    private void completeConfigurationChange(FlinkApplication app, String operation) {
        long previous = app.getApplicationVersionId();
        app.setApplicationVersionId(previous + 1);
        app.setLastUpdateTimestamp(Instant.now());
        app.setConditionalToken(UUID.randomUUID().toString());
        rememberVersion(app);
        ObjectNode info = JsonNodeFactory.instance.objectNode();
        String id = UUID.randomUUID().toString();
        info.put("OperationId", id);
        info.put("Operation", operation);
        info.put("StartTime", app.getLastUpdateTimestamp().toEpochMilli() / 1000.0);
        info.put("OperationStatus", app.getApplicationStatus() == ApplicationStatus.STARTING ? "IN_PROGRESS" : "SUCCESSFUL");
        if (app.getApplicationStatus() != ApplicationStatus.STARTING) {
            info.put("EndTime", app.getLastUpdateTimestamp().toEpochMilli() / 1000.0);
        }
        info.putObject("ApplicationVersionChangeDetails")
                .put("ApplicationVersionUpdatedFrom", previous)
                .put("ApplicationVersionUpdatedTo", app.getApplicationVersionId());
        app.getOperations().put(id, info);
        putApplication(app);
    }

    private static ObjectNode mergeFlinkConfiguration(ObjectNode current, JsonNode input, boolean update) {
        ObjectNode result = current == null ? JsonNodeFactory.instance.objectNode() : current.deepCopy();
        if (input == null || input.isMissingNode()) {
            return result;
        }
        if (!input.isObject()) {
            throw new AwsException("InvalidArgumentException", "Flink configuration must be an object", 400);
        }
        String suffix = update ? "Update" : "";
        Map<String, List<String>> fields = Map.of(
                "ParallelismConfiguration", List.of("ConfigurationType", "Parallelism", "ParallelismPerKPU", "AutoScalingEnabled"),
                "CheckpointConfiguration", List.of("ConfigurationType", "CheckpointingEnabled", "CheckpointInterval", "MinPauseBetweenCheckpoints"),
                "MonitoringConfiguration", List.of("ConfigurationType", "MetricsLevel", "LogLevel"));
        fields.forEach((section, names) -> {
            JsonNode source = input.path(section + suffix);
            if (source.isMissingNode()) {
                return;
            }
            if (!source.isObject()) {
                throw new AwsException("InvalidArgumentException", section + " must be an object", 400);
            }
            ObjectNode target = result.has(section) ? (ObjectNode) result.get(section) : result.putObject(section);
            for (String field : names) {
                JsonNode value = source.get(field + suffix);
                if (value != null) {
                    if (value.isNull() || ((field.equals("Parallelism") || field.equals("ParallelismPerKPU"))
                            && (!value.isIntegralNumber() || value.asLong() < 1))) {
                        throw new AwsException("InvalidArgumentException", "Invalid " + field, 400);
                    }
                    if (field.equals("ConfigurationType") && !List.of("DEFAULT", "CUSTOM").contains(value.asText())) {
                        throw new AwsException("InvalidArgumentException", "Invalid ConfigurationType", 400);
                    }
                    if (List.of("AutoScalingEnabled", "CheckpointingEnabled").contains(field) && !value.isBoolean()) {
                        throw new AwsException("InvalidArgumentException", field + " must be a boolean", 400);
                    }
                    if (List.of("CheckpointInterval", "MinPauseBetweenCheckpoints").contains(field)
                            && (!value.isIntegralNumber() || value.asLong() < (field.equals("CheckpointInterval") ? 1 : 0))) {
                        throw new AwsException("InvalidArgumentException", "Invalid " + field, 400);
                    }
                    if ((field.equals("MetricsLevel") && !List.of("APPLICATION", "TASK", "OPERATOR", "PARALLELISM").contains(value.asText()))
                            || (field.equals("LogLevel") && !List.of("DEBUG", "INFO", "WARN", "ERROR").contains(value.asText()))) {
                        throw new AwsException("InvalidArgumentException", "Invalid " + field, 400);
                    }
                    target.set(field, value.deepCopy());
                }
            }
            if ("DEFAULT".equals(target.path("ConfigurationType").asText())) {
                target.remove(names.stream().filter(field -> !field.equals("ConfigurationType")).toList());
            }
        });
        return result;
    }

    private FlinkApplication findByArn(String resourceArn) {
        if (resourceArn == null || resourceArn.isBlank()) {
            throw new AwsException("InvalidArgumentException", "ResourceARN is required", 400);
        }
        // Resource format is "application/<name>"; application names never contain '/'.
        int slash = resourceArn.lastIndexOf('/');
        if (slash < 0 || slash == resourceArn.length() - 1) {
            throw new AwsException("InvalidArgumentException", "Invalid resource ARN: " + resourceArn, 400);
        }
        String applicationName = resourceArn.substring(slash + 1);
        return findApplication(applicationName).filter(app -> resourceArn.equals(app.getApplicationArn()))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "No application found for ARN: " + resourceArn, 400));
    }

    private void startReadinessPoller() {
        poller.scheduleAtFixedRate(() -> {
            try {
                if (config.services().kinesisAnalytics().mock()) {
                    return;
                }
                for (FlinkApplication app : allApplications()) {
                    boolean changed = false;
                    if (app.getApplicationStatus() == ApplicationStatus.STARTING) {
                        String previousJobId = app.getFlinkJobId();
                        if (containerManager.advanceToRunning(app)) {
                            LOG.infov("Kinesis Analytics V2 application {0} is now RUNNING",
                                    app.getApplicationName());
                            app.setApplicationStatus(ApplicationStatus.RUNNING);
                            app.setLastUpdateTimestamp(Instant.now());
                            app.getOperations().values().stream()
                                    .filter(info -> "IN_PROGRESS".equals(info.path("OperationStatus").asText()))
                                    .forEach(info -> {
                                        info.put("OperationStatus", "SUCCESSFUL");
                                        info.put("EndTime", app.getLastUpdateTimestamp().toEpochMilli() / 1000.0);
                                    });
                            changed = true;
                        } else if (!Objects.equals(previousJobId, app.getFlinkJobId())) {
                            // The Flink job was just submitted (flinkJobId newly assigned) but hasn't
                            // reached RUNNING yet. Persist now rather than waiting for that: pendingJars
                            // (an in-process-only cache) is already cleared at this point, so if the
                            // emulator restarts before the next RUNNING-triggered persist, an unpersisted
                            // flinkJobId would leave the application stuck — it could never re-fetch the
                            // JAR to resubmit, nor know a job was already running to poll instead.
                            changed = true;
                        }
                    }
                    for (Snapshot snapshot : app.getSnapshots().values()) {
                        if (snapshot.getSnapshotStatus() == SnapshotStatus.CREATING
                                && containerManager.advanceSnapshot(app, snapshot)) {
                            changed = true;
                        }
                    }
                    if (changed) {
                        persistPolledApplication(app);
                    }
                }
            } catch (Exception e) {
                LOG.error("Error in Kinesis Analytics V2 readiness poller", e);
            }
        }, 1, 2, TimeUnit.SECONDS);
    }

    private List<FlinkApplication> allApplications() {
        if (storage instanceof AccountAwareStorageBackend<FlinkApplication> aware) {
            return aware.scanAllAccounts();
        }
        return storage.scan(k -> true);
    }

    private synchronized void persistPolledApplication(FlinkApplication app) {
        Optional<FlinkApplication> current = app.getAccountId() != null
                && storage instanceof AccountAwareStorageBackend<FlinkApplication> aware
                ? aware.getForAccount(app.getAccountId(), app.getApplicationArn())
                : storage.get(app.getApplicationArn());
        // A concurrent configuration change or delete supersedes this poll result.
        if (current.orElse(null) == app) {
            putApplication(app);
        }
    }

    private void putApplication(FlinkApplication app) {
        if (app.getAccountId() != null && storage instanceof AccountAwareStorageBackend<FlinkApplication> aware) {
            aware.putForAccount(app.getAccountId(), app.getApplicationArn(), app);
        } else {
            storage.put(app.getApplicationArn(), app);
        }
    }
}
