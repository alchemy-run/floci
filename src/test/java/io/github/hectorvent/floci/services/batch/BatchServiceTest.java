package io.github.hectorvent.floci.services.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.services.batch.model.BatchComputeEnvironment;
import io.github.hectorvent.floci.services.batch.model.BatchJob;
import io.github.hectorvent.floci.services.batch.model.BatchJobDefinition;
import io.github.hectorvent.floci.services.batch.model.BatchJobQueue;
import io.github.hectorvent.floci.services.batch.model.BatchRunResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BatchServiceTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void dockerTimeoutFailsWithoutRetryingRemainingAttempts() throws Exception {
        BatchDockerRunner runner = mock(BatchDockerRunner.class);
        when(runner.run(any(BatchJob.class), anyInt()))
                .thenReturn(new BatchRunResult(137, "Job timed out", "log-stream", 1L, 2L, true));
        BatchService service = dockerService(runner);

        String computeArn = service.createComputeEnvironment(json("""
                {"computeEnvironmentName":"timeout-ce","type":"MANAGED"}
                """), REGION).path("computeEnvironmentArn").asText();
        String queueArn = service.createJobQueue(json("""
                {
                  "jobQueueName":"timeout-queue",
                  "priority":1,
                  "computeEnvironmentOrder":[{"order":1,"computeEnvironment":"%s"}]
                }
                """.formatted(computeArn)), REGION).path("jobQueueArn").asText();
        String definitionArn = service.registerJobDefinition(json("""
                {
                  "jobDefinitionName":"timeout-job",
                  "type":"container",
                  "containerProperties":{"image":"public.ecr.aws/example/job:latest"},
                  "retryStrategy":{"attempts":3}
                }
                """), REGION).path("jobDefinitionArn").asText();

        String jobId = service.submitJob(json("""
                {
                  "jobName":"timeout-submit",
                  "jobQueue":"%s",
                  "jobDefinition":"%s",
                  "timeout":{"attemptDurationSeconds":60}
                }
                """.formatted(queueArn, definitionArn)), REGION).path("jobId").asText();

        JsonNode job = waitForJobStatus(service, jobId, "FAILED");
        assertNotNull(job);
        assertEquals("Job timed out", job.path("statusReason").asText());
        assertEquals(1, job.path("attempts").size());
        verify(runner, times(1)).run(any(BatchJob.class), anyInt());
    }

    @Test
    void dockerRetriesFailedAttemptAndCanSucceed() throws Exception {
        BatchDockerRunner runner = mock(BatchDockerRunner.class);
        when(runner.run(any(BatchJob.class), anyInt()))
                .thenReturn(new BatchRunResult(1, "first failed", "log-1", 1L, 2L, false))
                .thenReturn(new BatchRunResult(0, null, "log-2", 3L, 4L, false));
        BatchService service = dockerService(runner);

        String computeArn = service.createComputeEnvironment(json("""
                {"computeEnvironmentName":"retry-ce","type":"MANAGED"}
                """), REGION).path("computeEnvironmentArn").asText();
        String queueArn = service.createJobQueue(json("""
                {
                  "jobQueueName":"retry-queue",
                  "priority":1,
                  "computeEnvironmentOrder":[{"order":1,"computeEnvironment":"%s"}]
                }
                """.formatted(computeArn)), REGION).path("jobQueueArn").asText();
        String definitionArn = service.registerJobDefinition(json("""
                {
                  "jobDefinitionName":"retry-job",
                  "type":"container",
                  "containerProperties":{"image":"public.ecr.aws/example/job:latest"},
                  "retryStrategy":{"attempts":2}
                }
                """), REGION).path("jobDefinitionArn").asText();

        String jobId = service.submitJob(json("""
                {"jobName":"retry-submit","jobQueue":"%s","jobDefinition":"%s"}
                """.formatted(queueArn, definitionArn)), REGION).path("jobId").asText();

        JsonNode job = waitForJobStatus(service, jobId, "SUCCEEDED");
        assertNotNull(job);
        assertEquals(2, job.path("attempts").size());
        assertEquals(0, job.path("attempts").get(1).path("container").path("exitCode").asInt());
        verify(runner, times(2)).run(any(BatchJob.class), anyInt());
    }

    @Test
    void dockerRetryExhaustionFailsJobAndKeepsAttempts() throws Exception {
        BatchDockerRunner runner = mock(BatchDockerRunner.class);
        when(runner.run(any(BatchJob.class), anyInt()))
                .thenReturn(new BatchRunResult(1, "failed once", "log-1", 1L, 2L, false))
                .thenReturn(new BatchRunResult(2, "failed twice", "log-2", 3L, 4L, false));
        BatchService service = dockerService(runner);

        String computeArn = service.createComputeEnvironment(json("""
                {"computeEnvironmentName":"exhaust-ce","type":"MANAGED"}
                """), REGION).path("computeEnvironmentArn").asText();
        String queueArn = service.createJobQueue(json("""
                {
                  "jobQueueName":"exhaust-queue",
                  "priority":1,
                  "computeEnvironmentOrder":[{"order":1,"computeEnvironment":"%s"}]
                }
                """.formatted(computeArn)), REGION).path("jobQueueArn").asText();
        String definitionArn = service.registerJobDefinition(json("""
                {
                  "jobDefinitionName":"exhaust-job",
                  "type":"container",
                  "containerProperties":{"image":"public.ecr.aws/example/job:latest"},
                  "retryStrategy":{"attempts":2}
                }
                """), REGION).path("jobDefinitionArn").asText();

        String jobId = service.submitJob(json("""
                {"jobName":"exhaust-submit","jobQueue":"%s","jobDefinition":"%s"}
                """.formatted(queueArn, definitionArn)), REGION).path("jobId").asText();

        JsonNode job = waitForJobStatus(service, jobId, "FAILED");
        assertNotNull(job);
        assertEquals("failed twice", job.path("statusReason").asText());
        assertEquals(2, job.path("attempts").size());
        assertEquals(2, job.path("attempts").get(1).path("container").path("exitCode").asInt());
        verify(runner, times(2)).run(any(BatchJob.class), anyInt());
    }

    @Test
    void listJobsUsesStableJobIdTiebreakerForSameCreatedAt() throws Exception {
        ReverseScanJobStorage jobStore = new ReverseScanJobStorage();
        BatchService service = immediateService(jobStore);

        String computeArn = service.createComputeEnvironment(json("""
                {"computeEnvironmentName":"page-tie-ce","type":"MANAGED"}
                """), REGION).path("computeEnvironmentArn").asText();
        String queueArn = service.createJobQueue(json("""
                {
                  "jobQueueName":"page-tie-queue",
                  "priority":1,
                  "computeEnvironmentOrder":[{"order":1,"computeEnvironment":"%s"}]
                }
                """.formatted(computeArn)), REGION).path("jobQueueArn").asText();
        String definitionArn = service.registerJobDefinition(json("""
                {
                  "jobDefinitionName":"page-tie-job",
                  "type":"container",
                  "containerProperties":{"image":"public.ecr.aws/example/job:latest"}
                }
                """), REGION).path("jobDefinitionArn").asText();

        service.submitJob(json("""
                {"jobName":"page-tie-first","jobQueue":"%s","jobDefinition":"%s"}
                """.formatted(queueArn, definitionArn)), REGION);
        service.submitJob(json("""
                {"jobName":"page-tie-second","jobQueue":"%s","jobDefinition":"%s"}
                """.formatted(queueArn, definitionArn)), REGION);

        List<BatchJob> jobs = jobStore.scan(k -> true);
        assertEquals(2, jobs.size());
        BatchJob secondInserted = jobs.get(0);
        BatchJob firstInserted = jobs.get(1);
        secondInserted.setCreatedAt(123L);
        secondInserted.setJobId("b-job");
        firstInserted.setCreatedAt(123L);
        firstInserted.setJobId("a-job");

        JsonNode firstPage = service.listJobs(json("""
                {"jobQueue":"%s","jobStatus":"SUCCEEDED","maxResults":1}
                """.formatted(queueArn)));
        assertEquals("a-job", firstPage.path("jobSummaryList").get(0).path("jobId").asText());

        JsonNode secondPage = service.listJobs(json("""
                {"jobQueue":"%s","jobStatus":"SUCCEEDED","maxResults":1,"nextToken":"%s"}
                """.formatted(queueArn, firstPage.path("nextToken").asText())));
        assertEquals("b-job", secondPage.path("jobSummaryList").get(0).path("jobId").asText());
    }

    @Test
    void queuedSubmitLeavesJobRunnableSoCancelAndTerminateCanFailIt() throws Exception {
        BatchService service = queuedService();
        String queueArn = createQueue(service, "queued-ce", "queued-queue");
        String definitionArn = registerDefinition(service, "queued-job");

        String cancelId = service.submitJob(json("""
                {"jobName":"queued-cancel","jobQueue":"%s","jobDefinition":"%s"}
                """.formatted(queueArn, definitionArn)), REGION).path("jobId").asText();
        JsonNode submitted = service.describeJobs(json("{\"jobs\":[\"%s\"]}".formatted(cancelId)))
                .path("jobs").get(0);
        assertEquals("RUNNABLE", submitted.path("status").asText());
        assertTrue(submitted.path("jobQueue").asText().contains("job-queue/"));

        JsonNode listed = service.listJobs(json("""
                {"jobQueue":"%s","jobStatus":"RUNNABLE"}
                """.formatted(queueArn)));
        assertEquals(cancelId, listed.path("jobSummaryList").get(0).path("jobId").asText());

        JsonNode snapshotJob = service.getJobQueueSnapshot(json("{\"jobQueue\":\"%s\"}".formatted(queueArn)))
                .path("frontOfQueue").path("jobs").get(0);
        assertTrue(snapshotJob.path("jobArn").asText().endsWith("/" + cancelId));
        assertTrue(snapshotJob.has("earliestTimeAtPosition"));

        service.cancelJob(json("""
                {"jobId":"%s","reason":"alchemy e2e cancel test"}
                """.formatted(cancelId)));
        assertEquals("FAILED", service.describeJobs(json("{\"jobs\":[\"%s\"]}".formatted(cancelId)))
                .path("jobs").get(0).path("status").asText());

        String terminateId = service.submitJob(json("""
                {"jobName":"queued-terminate","jobQueue":"%s","jobDefinition":"%s"}
                """.formatted(queueArn, definitionArn)), REGION).path("jobId").asText();
        service.terminateJob(json("""
                {"jobId":"%s","reason":"alchemy e2e terminate test"}
                """.formatted(terminateId)));
        assertEquals("FAILED", service.describeJobs(json("{\"jobs\":[\"%s\"]}".formatted(terminateId)))
                .path("jobs").get(0).path("status").asText());
    }

    @Test
    void cancelAndTerminateAreNoOpsOnSucceededJobs() throws Exception {
        BatchService service = immediateService(new InMemoryStorage<>());
        String queueArn = createQueue(service, "terminal-ce", "terminal-queue");
        String definitionArn = registerDefinition(service, "terminal-job");
        String jobId = service.submitJob(json("""
                {"jobName":"already-done","jobQueue":"%s","jobDefinition":"%s"}
                """.formatted(queueArn, definitionArn)), REGION).path("jobId").asText();
        assertEquals("SUCCEEDED", service.describeJobs(json("{\"jobs\":[\"%s\"]}".formatted(jobId)))
                .path("jobs").get(0).path("status").asText());

        service.cancelJob(json("{\"jobId\":\"%s\",\"reason\":\"late cancel\"}".formatted(jobId)));
        service.terminateJob(json("{\"jobId\":\"%s\",\"reason\":\"late terminate\"}".formatted(jobId)));
        assertEquals("SUCCEEDED", service.describeJobs(json("{\"jobs\":[\"%s\"]}".formatted(jobId)))
                .path("jobs").get(0).path("status").asText());
    }

    @Test
    void deleteComputeEnvironmentRejectsAttachedQueueWithInUseMessage() throws Exception {
        BatchService service = immediateService(new InMemoryStorage<>());
        String envArn = service.createComputeEnvironment(json("""
                {"computeEnvironmentName":"inuse-ce","type":"UNMANAGED","unmanagedvCpus":4}
                """), REGION).path("computeEnvironmentArn").asText();
        service.createJobQueue(json("""
                {
                  "jobQueueName":"inuse-queue",
                  "priority":1,
                  "computeEnvironmentOrder":[{"order":1,"computeEnvironment":"%s"}]
                }
                """.formatted(envArn)), REGION);
        service.updateComputeEnvironment(json("""
                {"computeEnvironment":"inuse-ce","state":"DISABLED"}
                """));

        AwsException error = assertThrows(AwsException.class, () ->
                service.deleteComputeEnvironment(json("{\"computeEnvironment\":\"inuse-ce\"}")));
        assertEquals("ClientException", error.getErrorCode());
        assertTrue(error.getMessage().contains("found existing JobQueue relationship"));
    }

    @Test
    void missingQueueAndComputeEnvironmentMessagesMatchDistilled() throws Exception {
        BatchService service = immediateService(new InMemoryStorage<>());
        AwsException queue = assertThrows(AwsException.class, () ->
                service.updateJobQueue(json("{\"jobQueue\":\"missing-queue\"}")));
        assertTrue(queue.getMessage().matches("job-queue/.* does not exist"));

        AwsException env = assertThrows(AwsException.class, () ->
                service.updateComputeEnvironment(json("{\"computeEnvironment\":\"missing-ce\"}")));
        assertTrue(env.getMessage().matches("compute-environment/.* does not exist"));
    }

    @Test
    void unmanagedComputeEnvironmentRoundTripsUnmanagedvCpus() throws Exception {
        BatchService service = immediateService(new InMemoryStorage<>());
        service.createComputeEnvironment(json("""
                {"computeEnvironmentName":"unmanaged-ce","type":"UNMANAGED","unmanagedvCpus":4}
                """), REGION);
        JsonNode described = service.describeComputeEnvironments(json("""
                {"computeEnvironments":["unmanaged-ce"]}
                """)).path("computeEnvironments").get(0);
        assertEquals("UNMANAGED", described.path("type").asText());
        assertEquals(4, described.path("unmanagedvCpus").asInt());
        assertTrue(described.path("ecsClusterArn").isMissingNode());
    }

    private BatchService dockerService(BatchDockerRunner runner) {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.BatchServiceConfig batch = mock(EmulatorConfig.BatchServiceConfig.class);
        when(config.services()).thenReturn(services);
        when(services.batch()).thenReturn(batch);
        when(batch.runnerMode()).thenReturn("docker");

        return new BatchService(
                new InMemoryStorage<String, BatchJobDefinition>(),
                new InMemoryStorage<String, BatchJobQueue>(),
                new InMemoryStorage<String, BatchComputeEnvironment>(),
                new InMemoryStorage<String, BatchJob>(),
                new RegionResolver(REGION, ACCOUNT),
                config,
                objectMapper,
                runner);
    }

    private BatchService immediateService(StorageBackend<String, BatchJob> jobStore) {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.BatchServiceConfig batch = mock(EmulatorConfig.BatchServiceConfig.class);
        when(config.services()).thenReturn(services);
        when(services.batch()).thenReturn(batch);
        when(batch.runnerMode()).thenReturn("immediate");
        when(batch.immediateComplete()).thenReturn(true);

        return new BatchService(
                new InMemoryStorage<String, BatchJobDefinition>(),
                new InMemoryStorage<String, BatchJobQueue>(),
                new InMemoryStorage<String, BatchComputeEnvironment>(),
                jobStore,
                new RegionResolver(REGION, ACCOUNT),
                config,
                objectMapper,
                mock(BatchDockerRunner.class));
    }

    private BatchService queuedService() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.BatchServiceConfig batch = mock(EmulatorConfig.BatchServiceConfig.class);
        when(config.services()).thenReturn(services);
        when(services.batch()).thenReturn(batch);
        when(batch.runnerMode()).thenReturn("immediate");
        when(batch.immediateComplete()).thenReturn(false);

        return new BatchService(
                new InMemoryStorage<String, BatchJobDefinition>(),
                new InMemoryStorage<String, BatchJobQueue>(),
                new InMemoryStorage<String, BatchComputeEnvironment>(),
                new InMemoryStorage<String, BatchJob>(),
                new RegionResolver(REGION, ACCOUNT),
                config,
                objectMapper,
                mock(BatchDockerRunner.class));
    }

    private String createQueue(BatchService service, String envName, String queueName) throws Exception {
        String computeArn = service.createComputeEnvironment(json("""
                {"computeEnvironmentName":"%s","type":"UNMANAGED","unmanagedvCpus":4}
                """.formatted(envName)), REGION).path("computeEnvironmentArn").asText();
        return service.createJobQueue(json("""
                {
                  "jobQueueName":"%s",
                  "priority":1,
                  "computeEnvironmentOrder":[{"order":1,"computeEnvironment":"%s"}]
                }
                """.formatted(queueName, computeArn)), REGION).path("jobQueueArn").asText();
    }

    private String registerDefinition(BatchService service, String name) throws Exception {
        return service.registerJobDefinition(json("""
                {
                  "jobDefinitionName":"%s",
                  "type":"container",
                  "containerProperties":{"image":"public.ecr.aws/example/job:latest"}
                }
                """.formatted(name)), REGION).path("jobDefinitionArn").asText();
    }

    private ObjectNode json(String body) throws Exception {
        return (ObjectNode) objectMapper.readTree(body);
    }

    private JsonNode waitForJobStatus(BatchService service, String jobId, String status) throws Exception {
        ObjectNode request = objectMapper.createObjectNode();
        request.putArray("jobs").add(jobId);
        for (int i = 0; i < 100; i++) {
            JsonNode job = service.describeJobs(request).path("jobs").get(0);
            if (job != null && status.equals(job.path("status").asText())) {
                return job;
            }
            Thread.sleep(10);
        }
        return null;
    }

    private static final class ReverseScanJobStorage implements StorageBackend<String, BatchJob> {
        private final LinkedHashMap<String, BatchJob> store = new LinkedHashMap<>();

        @Override
        public void put(String key, BatchJob value) {
            store.put(key, value);
        }

        @Override
        public Optional<BatchJob> get(String key) {
            return Optional.ofNullable(store.get(key));
        }

        @Override
        public void delete(String key) {
            store.remove(key);
        }

        @Override
        public List<BatchJob> scan(Predicate<String> keyFilter) {
            List<BatchJob> values = new ArrayList<>();
            store.forEach((key, value) -> {
                if (keyFilter.test(key)) {
                    values.add(value);
                }
            });
            Collections.reverse(values);
            return values;
        }

        @Override
        public Set<String> keys() {
            return Set.copyOf(store.keySet());
        }

        @Override
        public void flush() {
        }

        @Override
        public void load() {
        }

        @Override
        public void clear() {
            store.clear();
        }
    }
}
