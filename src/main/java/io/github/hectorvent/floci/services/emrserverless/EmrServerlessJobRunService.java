package io.github.hectorvent.floci.services.emrserverless;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.core.common.SmithyValidationErrors;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.emrserverless.model.Application;
import io.github.hectorvent.floci.services.emrserverless.model.JobDriver;
import io.github.hectorvent.floci.services.emrserverless.model.JobRun;
import io.github.hectorvent.floci.services.emrserverless.model.JobRunAttemptSummary;
import io.github.hectorvent.floci.services.emrserverless.model.JobRunRecord;
import io.github.hectorvent.floci.services.emrserverless.model.JobRunSummary;
import io.github.hectorvent.floci.services.emrserverless.model.StartJobRunRequest;
import io.github.hectorvent.floci.services.iam.IamService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * EMR Serverless job runs. Nothing executes: a run follows the job run states and GetJobRun's
 * shape, and settles lazily when read. Times are kept to the millisecond, as AWS reports them. With the default {@code job-run-duration-seconds} of 0 a
 * BATCH run is SUCCESS as soon as it starts; a positive duration keeps it RUNNING that long, which
 * makes CancelJobRun and the execution timeout observable. A STREAMING run, which EMR Serverless
 * "expects to run for a long time", stays RUNNING until it is cancelled.
 *
 * <p>Every public method is synchronized: admitting a run (look up its client token, then write
 * it), settling one and cancelling one each read and then write a run, and must not interleave.
 */
@ApplicationScoped
public class EmrServerlessJobRunService {

    private static final Logger LOG = Logger.getLogger(EmrServerlessJobRunService.class);

    static final String STATE_RUNNING = "RUNNING";
    static final String STATE_SUCCESS = "SUCCESS";
    static final String STATE_CANCELLED = "CANCELLED";
    private static final String MODE_BATCH = "BATCH";
    private static final String MODE_STREAMING = "STREAMING";

    private static final Set<String> TERMINAL_STATES = Set.of(STATE_SUCCESS, "FAILED", STATE_CANCELLED);
    private static final List<String> STATES = List.of("SUBMITTED", "PENDING", "SCHEDULED", STATE_RUNNING,
            STATE_SUCCESS, "FAILED", "CANCELLING", STATE_CANCELLED, "QUEUED");
    // JobRunStateSet holds at most 8 states.
    private static final int MAX_FILTER_STATES = 8;
    private static final List<String> MODES = List.of(MODE_BATCH, MODE_STREAMING);
    // JobRun.executionTimeoutMinutes: "If you didn't specify a timeout, this value defaults to 720 minutes."
    private static final long DEFAULT_EXECUTION_TIMEOUT_MINUTES = 720;
    // The model's Duration, which bounds executionTimeoutMinutes.
    private static final long MAX_EXECUTION_TIMEOUT_MINUTES = 1_000_000;
    // The EMR Serverless 7.11.0 release notes: for BATCH job runs the maximum "is 7 days from this release
    // onwards", and the timeout "can no longer be set to 0".
    private static final long MAX_BATCH_EXECUTION_TIMEOUT_MINUTES = 10_080;
    private static final Pattern RELEASE_LABEL = Pattern.compile("emr-(\\d{1,6})\\.(\\d{1,6})(?:\\..*)?");
    // Nothing runs, so no run fails and none is retried: every run is its own one attempt.
    private static final int ATTEMPT = 1;
    private static final int MAX_LIST_RESULTS = 50;
    // IAMRoleArn's pattern, as the model and AWS's validation message spell it.
    private static final String ROLE_ARN_PATTERN =
            "arn:(aws[a-zA-Z0-9-]*):iam::([0-9]{12}):(role((\\u002F)|(\\u002F[\\u0021-\\u007F]+\\u002F))[\\w+=,.@-]+)";
    private static final Pattern ROLE_ARN = Pattern.compile(ROLE_ARN_PATTERN);

    private final AccountAwareStorageBackend<JobRunRecord> store;
    private final EmrServerlessService applications;
    private final IamService iamService;
    private final RegionResolver regionResolver;
    private final RequestContext requestContext;
    private final ObjectMapper objectMapper;
    private final int runDurationSeconds;
    private final Clock clock;

    @Inject
    public EmrServerlessJobRunService(StorageFactory storageFactory, EmrServerlessService applications,
                                      IamService iamService, RegionResolver regionResolver,
                                      RequestContext requestContext, ObjectMapper objectMapper,
                                      EmulatorConfig config, Clock clock) {
        this(storageFactory.create("emrserverless", "emr-serverless-job-runs.json", new TypeReference<>() {}),
                applications, iamService, regionResolver, requestContext, objectMapper,
                config.services().emrserverless().jobRunDurationSeconds(), clock);
    }

    EmrServerlessJobRunService(AccountAwareStorageBackend<JobRunRecord> store, EmrServerlessService applications,
                               IamService iamService, RegionResolver regionResolver, RequestContext requestContext,
                               ObjectMapper objectMapper, int runDurationSeconds, Clock clock) {
        if (runDurationSeconds < 0) {
            throw new IllegalArgumentException(
                    "floci.services.emrserverless.job-run-duration-seconds must not be negative: " + runDurationSeconds);
        }
        this.store = store;
        this.applications = applications;
        this.iamService = iamService;
        this.regionResolver = regionResolver;
        this.requestContext = requestContext;
        this.objectMapper = objectMapper;
        this.runDurationSeconds = runDurationSeconds;
        this.clock = clock;
    }

    /**
     * Starts a run, or returns the run an earlier request with the same client token started. The
     * token is StartJobRun's idempotency token; reused with a different request it answers
     * {@code ConflictException} "Request did not match the original", as AWS does.
     */
    public synchronized JobRun startJobRun(String applicationId, StartJobRunRequest request) {
        StartJobRunRequest body = request != null ? request : new StartJobRunRequest();
        validate(body);
        applications.getApplication(applicationId);
        JsonNode original = requestOf(applicationId, body);
        JobRunRecord existing = runWithClientToken(body.getClientToken());
        if (existing != null) {
            if (!original.equals(existing.getRequest())) {
                throw new AwsException("ConflictException", "Request did not match the original", 409);
            }
            return settle(existing).getJobRun();
        }
        // Before admission, which may auto-start the application: a refused run leaves it as it was.
        if (body.getTags() != null) {
            EmrServerlessService.validateTags(body.getTags());
        }
        JobRunRecord record = applications.admitJobRun(applicationId, app -> checkBatchTimeout(app, body),
                app -> newRun(app, body, original));
        LOG.infov("Started EMR Serverless job run {0} on application {1}", record.getJobRun().getJobRunId(),
                applicationId);
        return settle(record).getJobRun();
    }

    /**
     * The run, as its {@code attempt} saw it; without one, "the latest attempt". A run has one
     * attempt, so any other number names nothing. No source gives AWS's answer for an attempt a run
     * never made; ResourceNotFoundException, in Floci's words, is the modeled error that fits.
     */
    public synchronized JobRun getJobRun(String applicationId, String jobRunId, Integer attempt) {
        if (attempt != null) {
            String violation = SmithyValidationErrors.outOfRange("attempt", attempt, ATTEMPT, Integer.MAX_VALUE);
            if (violation != null) {
                throw SmithyValidationErrors.validationException(violation);
            }
        }
        applications.getApplication(applicationId);
        JobRunRecord record = findRun(applicationId, jobRunId);
        if (attempt != null && attempt != ATTEMPT) {
            throw new AwsException("ResourceNotFoundException",
                    "Job run " + jobRunId + " has no attempt " + attempt, 404);
        }
        return settle(record).getJobRun();
    }

    /** A run's attempts, which are its one attempt. */
    public synchronized PaginatedResult<JobRunAttemptSummary> listJobRunAttempts(String applicationId,
                                                                               String jobRunId,
                                                                               Integer maxResults,
                                                                               String nextToken) {
        if (maxResults != null) {
            String violation = SmithyValidationErrors.outOfRange("maxResults", maxResults, 1, MAX_LIST_RESULTS);
            if (violation != null) {
                throw SmithyValidationErrors.validationException(violation);
            }
        }
        applications.getApplication(applicationId);
        JobRunRecord record = settle(findRun(applicationId, jobRunId));
        PaginatedResult<JobRunRecord> page = Pagination.paginate(List.of(record),
                only -> String.format("%010d", ATTEMPT), maxResults != null ? maxResults : MAX_LIST_RESULTS,
                nextToken, String.join("|", "ListJobRunAttempts", applicationId, jobRunId),
                token -> new AwsException("ValidationException", "Invalid nextToken.", 400));
        return new PaginatedResult<>(
                page.items().stream().map(EmrServerlessJobRunService::toAttemptSummary).toList(),
                page.nextToken());
    }

    /**
     * The application's runs, newest first. ListJobRuns models no ResourceNotFoundException, so an
     * unknown application has no runs rather than an error. With several {@code states}, "the
     * resulting list will be grouped by the state", in the order the filter names them. A run
     * started without a mode is a BATCH run. A {@code nextToken} resumes only the listing it came
     * from: its cursor holds a state group, which means nothing under another application or filter.
     */
    public synchronized PaginatedResult<JobRunSummary> listJobRuns(String applicationId, List<String> states,
                                                                   Instant createdAtAfter, Instant createdAtBefore,
                                                                   String mode, Integer maxResults, String nextToken) {
        List<String> violations = new ArrayList<>();
        if (maxResults != null) {
            String violation = SmithyValidationErrors.outOfRange("maxResults", maxResults, 1, MAX_LIST_RESULTS);
            if (violation != null) {
                violations.add(violation);
            }
        }
        violations.addAll(EmrServerlessService.statesViolations(states, STATES, MAX_FILTER_STATES));
        if (mode != null && !MODES.contains(mode)) {
            violations.add(SmithyValidationErrors.notInEnum(mode, "mode", MODES));
        }
        if (!violations.isEmpty()) {
            throw SmithyValidationErrors.validationException(violations);
        }
        List<JobRunRecord> matching = new ArrayList<>();
        Map<String, String> cursors = new HashMap<>();
        for (JobRunRecord record : store.scan(key -> true)) {
            JobRun run = record.getJobRun();
            if (!applicationId.equals(run.getApplicationId()) || !inRequestRegion(run)) {
                continue;
            }
            settle(record);
            if ((states != null && !states.isEmpty() && !states.contains(run.getState()))
                    || (createdAtAfter != null && !run.getCreatedAt().isAfter(createdAtAfter))
                    || (createdAtBefore != null && !run.getCreatedAt().isBefore(createdAtBefore))
                    || (mode != null && !mode.equals(run.getMode() != null ? run.getMode() : MODE_BATCH))) {
                continue;
            }
            int group = states == null || states.isEmpty() ? 0 : states.indexOf(run.getState());
            cursors.put(run.getJobRunId(), String.format("%02d%020d%s", group,
                    Long.MAX_VALUE - run.getCreatedAt().toEpochMilli(), run.getJobRunId()));
            matching.add(record);
        }
        String namespace = String.join("|", "ListJobRuns", applicationId,
                String.valueOf(states != null ? states : List.of()), String.valueOf(createdAtAfter),
                String.valueOf(createdAtBefore), String.valueOf(mode));
        PaginatedResult<JobRunRecord> page = Pagination.paginate(matching,
                record -> cursors.get(record.getJobRun().getJobRunId()),
                maxResults != null ? maxResults : MAX_LIST_RESULTS, nextToken, namespace,
                token -> new AwsException("ValidationException", "Invalid nextToken.", 400));
        return new PaginatedResult<>(page.items().stream().map(EmrServerlessJobRunService::toSummary).toList(),
                page.nextToken());
    }

    /** Cancels a run that has not finished. A finished run is refused, with AWS's wording. */
    public synchronized JobRun cancelJobRun(String applicationId, String jobRunId) {
        applications.getApplication(applicationId);
        JobRunRecord record = settle(findRun(applicationId, jobRunId));
        if (isTerminal(record.getJobRun())) {
            throw new AwsException("ValidationException", "Job run is not in a cancellable state", 400);
        }
        finish(record, STATE_CANCELLED, clock.instant().truncatedTo(ChronoUnit.MILLIS));
        LOG.infov("Cancelled EMR Serverless job run {0} on application {1}", jobRunId, applicationId);
        return record.getJobRun();
    }

    public synchronized Map<String, String> listTags(EmrServerlessService.ResourceArn resource) {
        JobRun run = runFor(resource).getJobRun();
        return run.getTags() != null ? new LinkedHashMap<>(run.getTags()) : new LinkedHashMap<>();
    }

    public synchronized void tagResource(EmrServerlessService.ResourceArn resource, Map<String, String> tags) {
        JobRunRecord record = runFor(resource);
        record.getJobRun().setTags(EmrServerlessService.withTags(record.getJobRun().getTags(), tags));
        store.put(record.getJobRun().getJobRunId(), record);
    }

    public synchronized void untagResource(EmrServerlessService.ResourceArn resource, List<String> tagKeys) {
        JobRunRecord record = runFor(resource);
        record.getJobRun().setTags(EmrServerlessService.withoutTags(record.getJobRun().getTags(), tagKeys));
        store.put(record.getJobRun().getJobRunId(), record);
    }

    /** GetJobRun's {@code attempt}, an integer in the query string. */
    static Integer parseAttempt(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new AwsException("ValidationException", "attempt must be an integer.", 400);
        }
    }

    /**
     * A ListJobRuns time filter. Query-string timestamps are ISO 8601 in restJson1; epoch seconds
     * are accepted too.
     */
    static Instant parseTimestamp(String value, String member) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException notIso) {
            try {
                return Instant.ofEpochMilli(Math.round(Double.parseDouble(value) * 1000));
            } catch (NumberFormatException notEpoch) {
                throw new AwsException("ValidationException", member + " must be a timestamp.", 400);
            }
        }
    }

    private JobRunRecord newRun(Application app, StartJobRunRequest request, JsonNode original) {
        AwsArnUtils.Arn applicationArn = AwsArnUtils.parse(app.getArn());
        String jobRunId = EmrServerlessService.newId();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);

        JobRun run = new JobRun();
        run.setApplicationId(app.getApplicationId());
        run.setJobRunId(jobRunId);
        run.setName(request.getName());
        run.setArn(AwsArnUtils.Arn.of("emr-serverless", applicationArn.region(), applicationArn.accountId(),
                applicationArn.resource() + "/jobruns/" + jobRunId).toString());
        run.setCreatedBy(createdBy(applicationArn.accountId()));
        run.setCreatedAt(now);
        run.setUpdatedAt(now);
        run.setExecutionRole(request.getExecutionRoleArn());
        run.setExecutionIamPolicy(request.getExecutionIamPolicy());
        run.setState(STATE_RUNNING);
        run.setStateDetails("");
        run.setReleaseLabel(app.getReleaseLabel());
        run.setConfigurationOverrides(request.getConfigurationOverrides());
        run.setJobDriver(request.getJobDriver());
        run.setTags(request.getTags() != null ? new LinkedHashMap<>(request.getTags()) : new LinkedHashMap<>());
        run.setNetworkConfiguration(app.getNetworkConfiguration());
        run.setExecutionTimeoutMinutes(request.getExecutionTimeoutMinutes() != null
                ? request.getExecutionTimeoutMinutes() : DEFAULT_EXECUTION_TIMEOUT_MINUTES);
        run.setMode(request.getMode());
        run.setRetryPolicy(request.getRetryPolicy());
        run.setAttempt(ATTEMPT);
        run.setAttemptCreatedAt(now);
        run.setAttemptUpdatedAt(now);
        run.setStartedAt(now);

        JobRunRecord record = new JobRunRecord();
        record.setJobRun(run);
        record.setClientToken(request.getClientToken());
        record.setRequest(original);
        record.setApplicationType(app.getType());
        store.put(jobRunId, record);
        return record;
    }

    /**
     * Finishes a BATCH run that has been RUNNING for the configured duration, or cancels it at its
     * execution timeout when that comes first: "If the job run exceeds this duration, Amazon EMR
     * Serverless cancels it automatically."
     */
    private JobRunRecord settle(JobRunRecord record) {
        JobRun run = record.getJobRun();
        if (isTerminal(run) || MODE_STREAMING.equals(run.getMode())) {
            return record;
        }
        long timeoutSeconds = run.getExecutionTimeoutMinutes() == null || run.getExecutionTimeoutMinutes() == 0
                ? Long.MAX_VALUE : run.getExecutionTimeoutMinutes() * 60;
        if (runDurationSeconds > timeoutSeconds) {
            Instant timedOutAt = run.getStartedAt().plusSeconds(timeoutSeconds);
            if (!clock.instant().isBefore(timedOutAt)) {
                finish(record, STATE_CANCELLED, timedOutAt);
            }
            return record;
        }
        Instant finishedAt = run.getStartedAt().plusSeconds(runDurationSeconds);
        if (!clock.instant().isBefore(finishedAt)) {
            finish(record, STATE_SUCCESS, finishedAt);
        }
        return record;
    }

    private void finish(JobRunRecord record, String state, Instant endedAt) {
        JobRun run = record.getJobRun();
        run.setState(state);
        run.setEndedAt(endedAt);
        run.setUpdatedAt(endedAt);
        run.setAttemptUpdatedAt(endedAt);
        run.setTotalExecutionDurationSeconds((int) Duration.between(run.getStartedAt(), endedAt).toSeconds());
        store.put(run.getJobRunId(), record);
    }

    private JobRunRecord findRun(String applicationId, String jobRunId) {
        return store.get(jobRunId)
                .filter(record -> applicationId.equals(record.getJobRun().getApplicationId()))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Job run " + jobRunId + " not found", 404));
    }

    // The run a tagging ARN names. The ARN must be the run's own, so one naming another account names nothing,
    // and a deleted application's runs name nothing, as the application's own ARN does.
    private JobRunRecord runFor(EmrServerlessService.ResourceArn resource) {
        return store.get(resource.jobRunId())
                .filter(record -> resource.applicationId().equals(record.getJobRun().getApplicationId()))
                .filter(record -> resource.arn().equals(record.getJobRun().getArn()))
                .filter(record -> applications.isLive(resource.applicationId()))
                .orElseThrow(() -> EmrServerlessService.resourceNotFound(resource.arn()));
    }

    private JobRunRecord runWithClientToken(String clientToken) {
        for (JobRunRecord record : store.scan(key -> true)) {
            if (clientToken.equals(record.getClientToken()) && inRequestRegion(record.getJobRun())) {
                return record;
            }
        }
        return null;
    }

    /**
     * What a retry must repeat: the application and every member of the body but the token itself.
     * Read back from its JSON text, so that its numbers compare equal to those of a stored request
     * once storage has written and read it.
     */
    private JsonNode requestOf(String applicationId, StartJobRunRequest request) {
        ObjectNode node;
        try {
            node = (ObjectNode) objectMapper.readTree(objectMapper.writeValueAsString(request));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize a StartJobRun request", e);
        }
        node.remove("clientToken");
        node.put("applicationId", applicationId);
        return node;
    }

    // The caller, as GetCallerIdentity reports it; the account root for a key IAM does not know.
    private String createdBy(String accountId) {
        String accessKeyId = requestContext != null ? requestContext.getAccessKeyId() : null;
        return iamService.resolveCallerArn(accessKeyId)
                .orElseGet(() -> regionResolver.buildGlobalArn("iam", accountId, "root"));
    }

    private boolean inRequestRegion(JobRun run) {
        return regionResolver.getRegion().equals(AwsArnUtils.parse(run.getArn()).region());
    }

    private static boolean isTerminal(JobRun run) {
        return TERMINAL_STATES.contains(run.getState());
    }

    private static void validate(StartJobRunRequest request) {
        List<String> violations = new ArrayList<>();
        if (request.getClientToken() == null) {
            violations.add(notNull("clientToken"));
        }
        if (request.getExecutionRoleArn() == null) {
            violations.add(notNull("executionRoleArn"));
        } else if (!ROLE_ARN.matcher(request.getExecutionRoleArn()).matches()) {
            violations.add("Value at 'executionRoleArn' failed to satisfy constraint: "
                    + "Member must satisfy regular expression pattern: " + ROLE_ARN_PATTERN);
        }
        if (request.getMode() != null && !MODES.contains(request.getMode())) {
            violations.add(SmithyValidationErrors.notInEnum(request.getMode(), "mode", MODES));
        }
        if (request.getExecutionTimeoutMinutes() != null) {
            String violation = SmithyValidationErrors.outOfRange("executionTimeoutMinutes",
                    request.getExecutionTimeoutMinutes(), 0, MAX_EXECUTION_TIMEOUT_MINUTES);
            if (violation != null) {
                violations.add(violation);
            }
        }
        JobDriver driver = request.getJobDriver();
        if (driver != null && driver.getSparkSubmit() != null && driver.getSparkSubmit().getEntryPoint() == null) {
            violations.add(notNull("jobDriver.sparkSubmit.entryPoint"));
        }
        if (driver != null && driver.getHive() != null && driver.getHive().getQuery() == null) {
            violations.add(notNull("jobDriver.hive.query"));
        }
        if (!violations.isEmpty()) {
            throw SmithyValidationErrors.validationException(violations);
        }
        // StartJobRun's shape leaves jobDriver optional, but GetJobRun's JobRun requires one, so a run is
        // refused without it. JobDriver is a union, "so only one of the following members can be
        // specified". No source gives AWS's wording for a run without a driver, or with both.
        if (driver == null || (driver.getSparkSubmit() == null) == (driver.getHive() == null)) {
            throw new AwsException("ValidationException", "jobDriver must contain exactly one union member", 400);
        }
    }

    private static String notNull(String member) {
        return "Value null at '" + member + "' failed to satisfy constraint: Member must not be null";
    }

    /**
     * From Amazon EMR release 7.11.0 a BATCH run's timeout is 1 to 10080 minutes; an earlier release, or
     * a STREAMING run, keeps the model's 0 to 1,000,000, 0 meaning none. No source gives AWS's error, so
     * the wording is Floci's.
     */
    private static void checkBatchTimeout(Application app, StartJobRunRequest request) {
        Long minutes = request.getExecutionTimeoutMinutes();
        if (minutes == null || MODE_STREAMING.equals(request.getMode())
                || !boundsBatchTimeouts(app.getReleaseLabel())) {
            return;
        }
        if (minutes == 0 || minutes > MAX_BATCH_EXECUTION_TIMEOUT_MINUTES) {
            throw new AwsException("ValidationException", "executionTimeoutMinutes must be between 1 and "
                    + MAX_BATCH_EXECUTION_TIMEOUT_MINUTES + " for a BATCH job run on release emr-7.11.0 or later.",
                    400);
        }
    }

    private static boolean boundsBatchTimeouts(String releaseLabel) {
        Matcher matcher = RELEASE_LABEL.matcher(releaseLabel != null ? releaseLabel : "");
        if (!matcher.matches()) {
            return false;
        }
        int major = Integer.parseInt(matcher.group(1));
        return major > 7 || (major == 7 && Integer.parseInt(matcher.group(2)) >= 11);
    }

    private static JobRunSummary toSummary(JobRunRecord record) {
        JobRun run = record.getJobRun();
        JobRunSummary summary = new JobRunSummary();
        summary.setApplicationId(run.getApplicationId());
        summary.setId(run.getJobRunId());
        summary.setName(run.getName());
        summary.setMode(run.getMode());
        summary.setArn(run.getArn());
        summary.setCreatedBy(run.getCreatedBy());
        summary.setCreatedAt(run.getCreatedAt());
        summary.setUpdatedAt(run.getUpdatedAt());
        summary.setExecutionRole(run.getExecutionRole());
        summary.setState(run.getState());
        summary.setStateDetails(run.getStateDetails());
        summary.setReleaseLabel(run.getReleaseLabel());
        summary.setType(record.getApplicationType());
        summary.setAttempt(run.getAttempt());
        summary.setAttemptCreatedAt(run.getAttemptCreatedAt());
        summary.setAttemptUpdatedAt(run.getAttemptUpdatedAt());
        return summary;
    }

    private static JobRunAttemptSummary toAttemptSummary(JobRunRecord record) {
        JobRun run = record.getJobRun();
        JobRunAttemptSummary summary = new JobRunAttemptSummary();
        summary.setApplicationId(run.getApplicationId());
        summary.setId(run.getJobRunId());
        summary.setName(run.getName());
        summary.setMode(run.getMode());
        summary.setArn(run.getArn());
        summary.setCreatedBy(run.getCreatedBy());
        summary.setJobCreatedAt(run.getCreatedAt());
        summary.setCreatedAt(run.getCreatedAt());
        summary.setUpdatedAt(run.getUpdatedAt());
        summary.setExecutionRole(run.getExecutionRole());
        summary.setState(run.getState());
        summary.setStateDetails(run.getStateDetails());
        summary.setReleaseLabel(run.getReleaseLabel());
        summary.setType(record.getApplicationType());
        summary.setAttempt(ATTEMPT);
        return summary;
    }
}
