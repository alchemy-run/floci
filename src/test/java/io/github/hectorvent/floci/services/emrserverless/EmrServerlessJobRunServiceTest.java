package io.github.hectorvent.floci.services.emrserverless;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.services.emrserverless.model.CreateApplicationRequest;
import io.github.hectorvent.floci.services.emrserverless.model.JobDriver;
import io.github.hectorvent.floci.services.emrserverless.model.JobRun;
import io.github.hectorvent.floci.services.emrserverless.model.JobRunRecord;
import io.github.hectorvent.floci.services.emrserverless.model.StartJobRunRequest;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.testing.InMemoryStorageFactory;
import io.github.hectorvent.floci.testing.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EmrServerlessJobRunServiceTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT_ID = "000000000000";
    private static final String ROLE = "arn:aws:iam::000000000000:role/emr-serverless-job";

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private EmrServerlessService applications;
    private IamService iamService;
    private AccountAwareStorageBackend<JobRunRecord> store;
    private MutableClock clock;
    private String applicationId;

    @BeforeEach
    void setUp() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.defaultAccountId()).thenReturn(ACCOUNT_ID);
        clock = new MutableClock();
        applications = new EmrServerlessService(config, new InMemoryStorageFactory(ACCOUNT_ID),
                new RegionResolver(REGION, ACCOUNT_ID), clock);
        iamService = mock(IamService.class);
        when(iamService.resolveCallerArn(any())).thenReturn(Optional.empty());
        store = AccountAwareStorageBackend.inMemory(ACCOUNT_ID);

        CreateApplicationRequest create = new CreateApplicationRequest();
        create.setName("jobs");
        create.setReleaseLabel("emr-7.5.0");
        create.setType("SPARK");
        create.setClientToken(UUID.randomUUID().toString());
        applicationId = applications.createApplication(create).getApplicationId();
    }

    private EmrServerlessJobRunService service(int runDurationSeconds) {
        return new EmrServerlessJobRunService(store, applications, iamService, new RegionResolver(REGION, ACCOUNT_ID),
                null, objectMapper, runDurationSeconds, clock);
    }

    private static StartJobRunRequest request(String mode, Long executionTimeoutMinutes) {
        JobDriver.SparkSubmit sparkSubmit = new JobDriver.SparkSubmit();
        sparkSubmit.setEntryPoint("s3://bucket/jobs/app.jar");
        JobDriver driver = new JobDriver();
        driver.setSparkSubmit(sparkSubmit);
        StartJobRunRequest request = new StartJobRunRequest();
        request.setClientToken(UUID.randomUUID().toString());
        request.setExecutionRoleArn(ROLE);
        request.setJobDriver(driver);
        request.setMode(mode);
        request.setExecutionTimeoutMinutes(executionTimeoutMinutes);
        return request;
    }

    @Test
    void runSucceedsAsSoonAsItStartsByDefault() {
        JobRun run = service(0).startJobRun(applicationId, request(null, null));

        assertEquals("SUCCESS", run.getState());
        assertEquals(run.getCreatedAt(), run.getStartedAt());
        assertEquals(run.getCreatedAt(), run.getEndedAt());
        assertEquals(run.getCreatedAt(), run.getUpdatedAt());
        assertEquals(0, run.getTotalExecutionDurationSeconds());
    }

    @Test
    void positiveDurationKeepsTheRunRunningUntilItHasPassed() {
        EmrServerlessJobRunService service = service(30);
        JobRun started = service.startJobRun(applicationId, request(null, null));
        String jobRunId = started.getJobRunId();
        Instant submittedAt = started.getCreatedAt();

        clock.advance(Duration.ofSeconds(29));
        JobRun running = service.getJobRun(applicationId, jobRunId, null);
        assertEquals("RUNNING", running.getState());
        assertNull(running.getEndedAt());
        assertNull(running.getTotalExecutionDurationSeconds());

        clock.advance(Duration.ofSeconds(1));
        JobRun finished = service.getJobRun(applicationId, jobRunId, null);
        assertEquals("SUCCESS", finished.getState());
        assertEquals(submittedAt.plusSeconds(30), finished.getEndedAt());
        assertEquals(submittedAt.plusSeconds(30), finished.getUpdatedAt());
        assertEquals(30, finished.getTotalExecutionDurationSeconds());
    }

    @Test
    void cancellingARunningRunEndsItCancelled() {
        EmrServerlessJobRunService service = service(60);
        String jobRunId = service.startJobRun(applicationId, request(null, null)).getJobRunId();
        clock.advance(Duration.ofSeconds(10));

        JobRun cancelled = service.cancelJobRun(applicationId, jobRunId);

        assertEquals("CANCELLED", cancelled.getState());
        assertEquals(10, cancelled.getTotalExecutionDurationSeconds());
        clock.advance(Duration.ofMinutes(5));
        assertEquals("CANCELLED", service.getJobRun(applicationId, jobRunId, null).getState());
    }

    @Test
    void runIsCancelledAtItsExecutionTimeoutWhenItWouldRunLonger() {
        EmrServerlessJobRunService service = service(600);
        JobRun started = service.startJobRun(applicationId, request(null, 5L));
        String jobRunId = started.getJobRunId();
        Instant submittedAt = started.getCreatedAt();

        clock.advance(Duration.ofMinutes(5).minusSeconds(1));
        assertEquals("RUNNING", service.getJobRun(applicationId, jobRunId, null).getState());

        clock.advance(Duration.ofSeconds(1));
        JobRun timedOut = service.getJobRun(applicationId, jobRunId, null);
        assertEquals("CANCELLED", timedOut.getState());
        assertEquals(submittedAt.plus(Duration.ofMinutes(5)), timedOut.getEndedAt());
    }

    @Test
    void anAttemptTheRunNeverMadeIsRefusedBeforeTheRunSettles() {
        EmrServerlessJobRunService service = service(30);
        JobRun started = service.startJobRun(applicationId, request(null, null));
        String jobRunId = started.getJobRunId();
        clock.advance(Duration.ofSeconds(30));

        AwsException noAttempt = assertThrows(AwsException.class,
                () -> service.getJobRun(applicationId, jobRunId, 2));
        assertEquals("ResourceNotFoundException", noAttempt.getErrorCode());
        assertEquals("RUNNING", store.get(jobRunId).orElseThrow().getJobRun().getState());

        JobRun finished = service.getJobRun(applicationId, jobRunId, 1);
        assertEquals("SUCCESS", finished.getState());
        assertEquals(started.getCreatedAt().plusSeconds(30), finished.getEndedAt());
    }

    @Test
    void streamingRunIgnoresTheRunDuration() {
        EmrServerlessJobRunService service = service(0);
        String jobRunId = service.startJobRun(applicationId, request("STREAMING", null)).getJobRunId();

        clock.advance(Duration.ofDays(2));

        assertEquals("RUNNING", service.getJobRun(applicationId, jobRunId, null).getState());
    }

    @Test
    void retryIsRecognisedAfterTheStoredRunWasWrittenAndReadBack() throws Exception {
        EmrServerlessJobRunService service = service(0);
        StartJobRunRequest request = request("BATCH", 15L);
        request.setTags(Map.of("team", "data"));
        String jobRunId = service.startJobRun(applicationId, request).getJobRunId();

        // As persistent storage does: the record goes out as JSON and comes back.
        JobRunRecord stored = store.get(jobRunId).orElseThrow();
        store.put(jobRunId, objectMapper.readValue(objectMapper.writeValueAsString(stored), JobRunRecord.class));

        assertEquals(jobRunId, service.startJobRun(applicationId, request).getJobRunId());
        request.setExecutionTimeoutMinutes(30L);
        AwsException conflict = assertThrows(AwsException.class, () -> service.startJobRun(applicationId, request));
        assertEquals("ConflictException", conflict.getErrorCode());
        assertEquals(409, conflict.getHttpStatus());
    }

    @Test
    void negativeRunDurationIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> service(-1));
    }

    @Test
    void parseTimestampReadsIso8601AndEpochSeconds() {
        assertEquals(Instant.parse("2026-10-21T12:00:00Z"),
                EmrServerlessJobRunService.parseTimestamp("2026-10-21T12:00:00Z", "createdAtAfter"));
        assertEquals(Instant.parse("2026-10-21T12:00:00.500Z"),
                EmrServerlessJobRunService.parseTimestamp("1792584000.5", "createdAtAfter"));
        AwsException invalid = assertThrows(AwsException.class,
                () -> EmrServerlessJobRunService.parseTimestamp("yesterday", "createdAtAfter"));
        assertEquals("ValidationException", invalid.getErrorCode());
    }
}
