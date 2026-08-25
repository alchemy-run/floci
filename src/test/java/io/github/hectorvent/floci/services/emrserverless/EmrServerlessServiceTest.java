package io.github.hectorvent.floci.services.emrserverless;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.emrserverless.model.Application;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmrServerlessServiceTest {

    private static final String REGION = "us-east-1";
    private static final String MISSING_ID = "00abcdefabcdef01";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final EmrServerlessService service = new EmrServerlessService(
            new InMemoryStorage<>(),
            new RegionResolver(REGION, "000000000000"),
            objectMapper);

    @Test
    void getMissingApplicationThrowsResourceNotFound() {
        AwsException error = assertThrows(
                AwsException.class,
                () -> service.getApplication(REGION, MISSING_ID));
        assertEquals("ResourceNotFoundException", error.getErrorCode());
        assertEquals(404, error.getHttpStatus());
    }

    @Test
    void createThenGetRoundTripsNameTypeTagsAndAutoStop() throws Exception {
        Application created = service.createApplication(REGION, objectMapper.readTree("""
                {
                  "name": "alchemy-test-emrs-app",
                  "releaseLabel": "emr-7.5.0",
                  "type": "SPARK",
                  "clientToken": "token-one",
                  "autoStartConfiguration": {"enabled": true},
                  "autoStopConfiguration": {"enabled": true, "idleTimeoutMinutes": 15},
                  "tags": {"purpose": "alchemy-test"}
                }
                """));

        assertEquals("alchemy-test-emrs-app", created.getName());
        assertTrue(created.getArn().contains("/applications/"));
        assertEquals("CREATED", created.getState());
        assertEquals("Spark", created.getType());
        assertEquals(Integer.valueOf(15), created.getIdleTimeoutMinutes());
        assertEquals("alchemy-test", created.getTags().get("purpose"));

        Application fetched = service.getApplication(REGION, created.getApplicationId());
        assertEquals(created.getApplicationId(), fetched.getApplicationId());
        assertEquals("CREATED", fetched.getState());
        assertEquals("Spark", fetched.getType());
    }

    @Test
    void duplicateNameConflictsAndDeleteRemovesApplication() throws Exception {
        service.createApplication(REGION, objectMapper.readTree("""
                {
                  "name": "alchemy-dup",
                  "releaseLabel": "emr-7.5.0",
                  "type": "SPARK",
                  "clientToken": "token-dup-1"
                }
                """));

        AwsException conflict = assertThrows(
                AwsException.class,
                () -> service.createApplication(REGION, objectMapper.readTree("""
                        {
                          "name": "alchemy-dup",
                          "releaseLabel": "emr-7.5.0",
                          "type": "SPARK",
                          "clientToken": "token-dup-2"
                        }
                        """)));
        assertEquals("ConflictException", conflict.getErrorCode());
        assertEquals(409, conflict.getHttpStatus());

        Application created = service.createApplication(REGION, objectMapper.readTree("""
                {
                  "name": "alchemy-delete",
                  "releaseLabel": "emr-7.5.0",
                  "type": "SPARK",
                  "clientToken": "token-delete"
                }
                """));
        service.deleteApplication(REGION, created.getApplicationId());
        AwsException missing = assertThrows(
                AwsException.class,
                () -> service.getApplication(REGION, created.getApplicationId()));
        assertEquals("ResourceNotFoundException", missing.getErrorCode());
    }

    @Test
    void jobRunStartCancelAndListSessions() throws Exception {
        Application application = service.createApplication(REGION, objectMapper.readTree("""
                {
                  "name": "alchemy-jobs",
                  "releaseLabel": "emr-7.5.0",
                  "type": "SPARK",
                  "clientToken": "token-jobs",
                  "autoStartConfiguration": {"enabled": true}
                }
                """));
        String applicationId = application.getApplicationId();

        assertTrue(service.listJobRuns(REGION, applicationId, null).isEmpty());
        assertTrue(service.listSessions(REGION, applicationId, null).isEmpty());

        AwsException missingJob = assertThrows(
                AwsException.class,
                () -> service.getJobRun(REGION, applicationId, MISSING_ID));
        assertEquals("ResourceNotFoundException", missingJob.getErrorCode());
        assertEquals(404, missingJob.getHttpStatus());

        AwsException dashboard = assertThrows(
                AwsException.class,
                () -> service.getResourceDashboard(REGION, applicationId));
        assertEquals("AccessDeniedException", dashboard.getErrorCode());
        assertEquals(403, dashboard.getHttpStatus());

        AwsException missingDashboard = assertThrows(
                AwsException.class,
                () -> service.getResourceDashboard(REGION, MISSING_ID));
        assertEquals("AccessDeniedException", missingDashboard.getErrorCode());
        assertEquals(403, missingDashboard.getHttpStatus());

        AwsException sessionStart = assertThrows(
                AwsException.class,
                () -> service.startSession(REGION, applicationId, objectMapper.readTree("""
                        {
                          "executionRoleArn": "arn:aws:iam::000000000000:role/JobRole",
                          "clientToken": "session-token"
                        }
                        """)));
        assertEquals("ValidationException", sessionStart.getErrorCode());

        io.github.hectorvent.floci.services.emrserverless.model.JobRun jobRun =
                service.startJobRun(REGION, applicationId, objectMapper.readTree("""
                        {
                          "clientToken": "job-token",
                          "executionRoleArn": "arn:aws:iam::000000000000:role/JobRole",
                          "jobDriver": {"sparkSubmit": {"entryPoint": "local:///pi.py"}}
                        }
                        """));
        assertTrue(jobRun.getArn().contains("/jobruns/"));
        assertEquals("PENDING", jobRun.getState());
        assertEquals("STARTED", service.getApplication(REGION, applicationId).getState());

        io.github.hectorvent.floci.services.emrserverless.model.JobRun cancelled =
                service.cancelJobRun(REGION, applicationId, jobRun.getJobRunId());
        assertEquals("CANCELLED", cancelled.getState());
        assertEquals(jobRun.getJobRunId(), service.listJobRuns(REGION, applicationId, null).getFirst().getJobRunId());

        service.stopApplication(REGION, applicationId);
        assertEquals("STOPPED", service.getApplication(REGION, applicationId).getState());
    }
}
