package io.github.hectorvent.floci.services.codebuild;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.codebuild.model.Build;
import io.github.hectorvent.floci.services.codebuild.model.Project;
import io.github.hectorvent.floci.services.codebuild.model.ProjectArtifacts;
import io.github.hectorvent.floci.services.codebuild.model.ProjectEnvironment;
import io.github.hectorvent.floci.services.codebuild.model.ProjectSource;
import io.github.hectorvent.floci.services.codebuild.model.ReportGroup;
import io.github.hectorvent.floci.services.codebuild.model.SourceCredential;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * Verifies CodeBuild durable resources survive a restart. Two service instances share the same
 * {@link StorageFactory} backends; the second simulates a process restart reloading from disk.
 */
class CodeBuildServicePersistenceTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final String OTHER_ACCOUNT = "111111111111";

    @Test
    void durableResourcesSurviveRestart() {
        SharedStorageFactory storage = new SharedStorageFactory();

        CodeBuildService first = serviceWithStorage(storage);
        first.createProject(REGION, ACCOUNT, "build-proj", "demo",
                source("GITHUB"), null, null, artifacts("NO_ARTIFACTS"), null,
                new ProjectEnvironment(), "arn:aws:iam::" + ACCOUNT + ":role/cb",
                30, null, null, List.of(Map.of("key", "team", "value", "platform")), null, null, null);
        first.createReportGroup(REGION, ACCOUNT, "rg-1", "TEST", null,
                List.of(Map.of("key", "env", "value", "test")));
        SourceCredential cred = first.importSourceCredentials(REGION, ACCOUNT,
                "tok-secret", "GITHUB", "PERSONAL_ACCESS_TOKEN", true);

        CodeBuildService reloaded = serviceWithStorage(storage);

        List<Project> projects = reloaded.batchGetProjects(REGION, List.of("build-proj"));
        assertEquals(1, projects.size());
        assertEquals("platform", projects.getFirst().getTags().getFirst().get("value"));
        assertEquals(List.of("rg-1"), reloaded.listReportGroups(REGION).stream()
                .map(arn -> reloaded.batchGetReportGroups(REGION, List.of(arn)).getFirst().getName()).toList());
        List<SourceCredential> creds = reloaded.listSourceCredentials(REGION);
        assertEquals(1, creds.size());
        assertEquals(cred.getArn(), creds.getFirst().getArn());
    }

    @Test
    void projectUpdateAndDeleteArePersistedAfterRestart() {
        SharedStorageFactory storage = new SharedStorageFactory();

        CodeBuildService first = serviceWithStorage(storage);
        first.createProject(REGION, ACCOUNT, "p1", "original",
                source("GITHUB"), null, null, artifacts("NO_ARTIFACTS"), null,
                new ProjectEnvironment(), "arn:aws:iam::" + ACCOUNT + ":role/cb",
                null, null, null, null, null, null, null);
        // in-place mutation: update must be written back through StorageBackedMap
        first.updateProject(REGION, "p1", "updated", null, null, null, null, null,
                null, null, null, null, null, null, null, null, null);
        first.createProject(REGION, ACCOUNT, "p2", "to-delete",
                source("GITHUB"), null, null, artifacts("NO_ARTIFACTS"), null,
                new ProjectEnvironment(), "arn:aws:iam::" + ACCOUNT + ":role/cb",
                null, null, null, null, null, null, null);
        first.deleteProject(REGION, "p2");

        CodeBuildService reloaded = serviceWithStorage(storage);
        assertEquals("updated", reloaded.batchGetProjects(REGION, List.of("p1")).getFirst().getDescription());
        assertTrue(reloaded.batchGetProjects(REGION, List.of("p2")).isEmpty(),
                "deleted project must not reappear after restart");
        assertNull(reloaded.batchGetProjects(REGION, List.of("p2")).stream().findFirst().orElse(null));
    }

    @Test
    void resourcePolicyUpdatesAndDeletesSurviveRestart() {
        SharedStorageFactory storage = new SharedStorageFactory();
        CodeBuildService first = serviceWithStorage(storage);
        Project project = first.createProject(REGION, ACCOUNT, "policy-project", null,
                source("NO_SOURCE"), null, null, artifacts("NO_ARTIFACTS"), null,
                new ProjectEnvironment(), "arn:aws:iam::" + ACCOUNT + ":role/cb",
                null, null, null, null, null, null, null);
        ReportGroup group = first.createReportGroup(REGION, ACCOUNT, "policy-reports", "TEST", null, null);
        String projectPolicy = policy(project.getArn(), "BatchGetProjects");
        String groupPolicy = policy(group.getArn(), "BatchGetReportGroups");
        first.putResourcePolicy(REGION, ACCOUNT, project.getArn(), projectPolicy);
        first.putResourcePolicy(REGION, ACCOUNT, group.getArn(), groupPolicy);

        CodeBuildService reloaded = serviceWithStorage(storage);
        assertEquals(projectPolicy, reloaded.getResourcePolicy(REGION, ACCOUNT, project.getArn()));
        assertEquals(groupPolicy, reloaded.getResourcePolicy(REGION, ACCOUNT, group.getArn()));
        String replacement = projectPolicy.replace("Allow", "Deny");
        reloaded.putResourcePolicy(REGION, ACCOUNT, project.getArn(), replacement);
        reloaded.deleteResourcePolicy(REGION, ACCOUNT, group.getArn());

        CodeBuildService updated = serviceWithStorage(storage);
        assertEquals(replacement, updated.getResourcePolicy(REGION, ACCOUNT, project.getArn()));
        assertNull(updated.getResourcePolicy(REGION, ACCOUNT, group.getArn()));
        updated.putResourcePolicy(REGION, ACCOUNT, group.getArn(), groupPolicy);
        updated.deleteProject(REGION, project.getName());
        updated.deleteReportGroup(REGION, group.getArn());

        CodeBuildService deleted = serviceWithStorage(storage);
        deleted.createProject(REGION, ACCOUNT, project.getName(), null,
                source("NO_SOURCE"), null, null, artifacts("NO_ARTIFACTS"), null,
                new ProjectEnvironment(), "arn:aws:iam::" + ACCOUNT + ":role/cb",
                null, null, null, null, null, null, null);
        deleted.createReportGroup(REGION, ACCOUNT, group.getName(), "TEST", null, null);
        assertNull(deleted.getResourcePolicy(REGION, ACCOUNT, project.getArn()));
        assertNull(deleted.getResourcePolicy(REGION, ACCOUNT, group.getArn()));
    }

    private static String policy(String arn, String action) {
        return """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                "Principal":{"AWS":"arn:aws:iam::000000000000:root"},
                "Action":"codebuild:%s","Resource":"%s"}]}
                """.formatted(action, arn);
    }

    @Test
    void buildNumbersContinueAfterRestart() {
        SharedStorageFactory storage = new SharedStorageFactory();

        CodeBuildService first = serviceWithStorage(storage);
        first.createProject(REGION, ACCOUNT, "p1", "demo",
                source("NO_SOURCE"), null, null, artifacts("NO_ARTIFACTS"), null,
                new ProjectEnvironment(), "arn:aws:iam::" + ACCOUNT + ":role/cb",
                null, null, null, null, null, null, null);
        first.startBuild(REGION, ACCOUNT, "p1", null,
                null, null, null, null, null, null);
        Build second = first.startBuild(REGION, ACCOUNT, "p1", null,
                null, null, null, null, null, null);

        CodeBuildService reloaded = serviceWithStorage(storage);
        Build third = reloaded.startBuild(REGION, ACCOUNT, "p1", null,
                null, null, null, null, null, null);

        assertEquals(2, second.getBuildNumber());
        assertEquals(3, third.getBuildNumber());
        assertEquals("p1:3", third.getId());
    }

    @Test
    void buildNumbersAreIsolatedByAccountAcrossRestart() {
        SharedStorageFactory storage = new SharedStorageFactory();

        CodeBuildService first = serviceWithStorage(storage);
        first.createProject(REGION, ACCOUNT, "p1", "demo",
                source("NO_SOURCE"), null, null, artifacts("NO_ARTIFACTS"), null,
                new ProjectEnvironment(), "arn:aws:iam::" + ACCOUNT + ":role/cb",
                null, null, null, null, null, null, null);

        Build accountBuild = first.startBuild(REGION, ACCOUNT, "p1", null,
                null, null, null, null, null, null);
        Build otherAccountBuild = first.startBuild(REGION, OTHER_ACCOUNT, "p1", null,
                null, null, null, null, null, null);

        assertEquals(1, accountBuild.getBuildNumber());
        assertEquals(1, otherAccountBuild.getBuildNumber());
        assertEquals(accountBuild.getId(), otherAccountBuild.getId());
        assertEquals(accountBuild.getArn(), first.getBuild(REGION, ACCOUNT, accountBuild.getId()).getArn());
        assertEquals(otherAccountBuild.getArn(),
                first.getBuild(REGION, OTHER_ACCOUNT, otherAccountBuild.getId()).getArn());

        CodeBuildService reloaded = serviceWithStorage(storage);

        assertEquals(2, reloaded.startBuild(REGION, ACCOUNT, "p1", null,
                null, null, null, null, null, null).getBuildNumber());
        assertEquals(2, reloaded.startBuild(REGION, OTHER_ACCOUNT, "p1", null,
                null, null, null, null, null, null).getBuildNumber());
    }

    @Test
    void startAndRetryBuildResponsesUseAcceptedBuildSnapshot() {
        CodeBuildRunner runner = mock(CodeBuildRunner.class);
        doAnswer(invocation -> {
            Build build = invocation.getArgument(1, Build.class);
            build.setBuildStatus("FAILED");
            build.setBuildComplete(true);
            build.setCurrentPhase("COMPLETED");
            return null;
        }).when(runner).startBuild(any(), any(), any(), any());

        CodeBuildService service = serviceWithStorage(new SharedStorageFactory(), runner);
        service.createProject(REGION, ACCOUNT, "p1", "demo",
                source("NO_SOURCE"), null, null, artifacts("NO_ARTIFACTS"), null,
                new ProjectEnvironment(), "arn:aws:iam::" + ACCOUNT + ":role/cb",
                null, null, null, null, null, null, null);

        Build startResponse = service.startBuild(REGION, ACCOUNT, "p1", null,
                null, null, null, null, null, null);
        assertEquals("IN_PROGRESS", startResponse.getBuildStatus());
        assertEquals(false, startResponse.getBuildComplete());
        assertEquals("SUBMITTED", startResponse.getCurrentPhase());
        assertEquals("FAILED", service.getBuild(REGION, ACCOUNT, startResponse.getId()).getBuildStatus());

        Build retryResponse = service.retryBuild(REGION, ACCOUNT, startResponse.getId());
        assertEquals("IN_PROGRESS", retryResponse.getBuildStatus());
        assertEquals(false, retryResponse.getBuildComplete());
        assertEquals("SUBMITTED", retryResponse.getCurrentPhase());
        assertTrue(retryResponse.getBuildNumber() > startResponse.getBuildNumber());
        assertEquals("FAILED", service.getBuild(REGION, ACCOUNT, retryResponse.getId()).getBuildStatus());
    }

    @Test
    void batchAndReportQueriesReadPersistedRecordsAndDeleteAcrossRestart() {
        SharedStorageFactory storage = new SharedStorageFactory();
        ObjectMapper mapper = new ObjectMapper();
        CodeBuildService first = serviceWithStorage(storage);
        first.createProject(REGION, ACCOUNT, "query-project", null,
                source("NO_SOURCE"), null, null, artifacts("NO_ARTIFACTS"), null,
                new ProjectEnvironment(), "arn:aws:iam::" + ACCOUNT + ":role/cb",
                null, null, null, null, null, null, null);
        first.configureBuildBatch(REGION, "query-project", Map.of("serviceRole", "batch-role"));
        String group = first.createReportGroup(REGION, ACCOUNT, "query-reports", "TEST", null, null).getArn();
        String batchId = "query-project:stored";
        String batchArn = "arn:aws:codebuild:" + REGION + ":" + ACCOUNT + ":build-batch/" + batchId;
        String reportArn = group.replace(":report-group/", ":report/") + ":stored";
        JsonNode batch = mapper.valueToTree(Map.of("id", batchId, "arn", batchArn,
                "projectName", "query-project", "complete", true, "startTime", 1));
        JsonNode report = mapper.valueToTree(Map.of("arn", reportArn, "reportGroupArn", group,
                "created", 1, "testCases", List.of(Map.of("name", "stored-case", "status", "SUCCEEDED"))));
        storage.create("codebuild", "codebuild-build-batches.json", new TypeReference<Map<String, JsonNode>>() {})
                .put(batchArn, batch);
        storage.create("codebuild", "codebuild-reports.json", new TypeReference<Map<String, JsonNode>>() {})
                .put(reportArn, report);
        storage.create("codebuild", "codebuild-reports.json", new TypeReference<Map<String, JsonNode>>() {})
                .put(reportArn + "-second", mapper.valueToTree(Map.of("arn", reportArn + "-second",
                        "reportGroupArn", group, "created", 2)));

        CodeBuildService reloaded = serviceWithStorage(storage);
        assertEquals(Map.of("serviceRole", "batch-role"),
                reloaded.batchGetProjects(REGION, List.of("query-project")).getFirst().getBuildBatchConfig());
        assertEquals(List.of(batch), reloaded.batchGetBuildBatches(REGION, ACCOUNT, List.of(batchId))
                .get("buildBatches"));
        assertEquals(List.of(batchId), reloaded.listBuildBatchesForProject(REGION, ACCOUNT,
                mapper.valueToTree(Map.of("projectName", "query-project"))).get("ids"));
        assertEquals(List.of(), reloaded.batchGetBuildBatches(REGION, OTHER_ACCOUNT, List.of(batchId))
                .get("buildBatches"));
        assertEquals(List.of(), reloaded.batchGetBuildBatches("us-west-2", ACCOUNT, List.of(batchId))
                .get("buildBatches"));
        assertEquals(List.of(report), reloaded.batchGetReports(REGION, ACCOUNT, List.of(reportArn)).get("reports"));
        assertEquals(List.of(report.path("testCases").get(0)), reloaded.describeReport(REGION, ACCOUNT,
                mapper.valueToTree(Map.of("reportArn", reportArn)), "testCases").get("testCases"));
        assertEquals(Map.of("reports", List.of(reportArn + "-second"), "nextToken", "1"),
                reloaded.listReportsForReportGroup(REGION, ACCOUNT,
                        mapper.valueToTree(Map.of("reportGroupArn", group, "maxResults", 1))));
        assertEquals(Map.of("reports", List.of(reportArn)), reloaded.listReportsForReportGroup(REGION, ACCOUNT,
                mapper.valueToTree(Map.of("reportGroupArn", group, "maxResults", 1, "nextToken", "1"))));
        reloaded.deleteBuildBatch(REGION, ACCOUNT, batchArn);
        reloaded.deleteReport(REGION, ACCOUNT, reportArn);
        CodeBuildService deleted = serviceWithStorage(storage);
        assertEquals(List.of(batchId), deleted.batchGetBuildBatches(REGION, ACCOUNT, List.of(batchId))
                .get("buildBatchesNotFound"));
        assertEquals(List.of(reportArn), deleted.batchGetReports(REGION, ACCOUNT, List.of(reportArn))
                .get("reportsNotFound"));
        assertEquals("InvalidInputException", assertThrows(AwsException.class,
                () -> deleted.deleteReportGroup(REGION, group)).getErrorCode());
        deleted.deleteReportGroup(REGION, group, true);
        CodeBuildService withoutGroup = serviceWithStorage(storage);
        assertTrue(withoutGroup.batchGetReportGroups(REGION, List.of(group)).isEmpty());
        assertEquals(List.of(reportArn + "-second"), withoutGroup
                .batchGetReports(REGION, ACCOUNT, List.of(reportArn + "-second")).get("reportsNotFound"));
    }

    private static ProjectSource source(String type) {
        ProjectSource s = new ProjectSource();
        s.setType(type);
        return s;
    }

    private static ProjectArtifacts artifacts(String type) {
        ProjectArtifacts a = new ProjectArtifacts();
        a.setType(type);
        return a;
    }

    private static CodeBuildService serviceWithStorage(StorageFactory storage) {
        return serviceWithStorage(storage, mock(CodeBuildRunner.class));
    }

    private static CodeBuildService serviceWithStorage(StorageFactory storage, CodeBuildRunner runner) {
        CodeBuildService service = new CodeBuildService(
                runner, mock(EmulatorConfig.class), storage, new ObjectMapper());
        service.initializeStorage();
        return service;
    }

    private static final class SharedStorageFactory extends StorageFactory {
        private final Map<String, StorageBackend<String, ?>> stores = new HashMap<>();

        private SharedStorageFactory() {
            super(null, null);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <V> AccountAwareStorageBackend<V> create(String serviceName,
                                                    String fileName,
                                                    TypeReference<Map<String, V>> typeReference) {
            return (AccountAwareStorageBackend<V>) stores.computeIfAbsent(fileName, ignored -> AccountAwareStorageBackend.inMemory("000000000000"));
        }
    }
}
