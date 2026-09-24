package io.github.hectorvent.floci.services.codebuild;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.codebuild.model.Build;
import io.github.hectorvent.floci.services.codebuild.model.Project;
import io.github.hectorvent.floci.services.codebuild.model.ProjectArtifacts;
import io.github.hectorvent.floci.services.codebuild.model.ProjectEnvironment;
import io.github.hectorvent.floci.services.codebuild.model.ProjectSource;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class CodeBuildServiceTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";

    @Test
    void resourcePoliciesRoundTripReplaceAndDeleteForBothResourceTypes() {
        CodeBuildService service = service();
        Project project = createProject(service, "policy-project");
        String groupArn = service.createReportGroup(REGION, ACCOUNT, "policy-reports", "TEST", null, null).getArn();
        for (String arn : List.of(project.getArn(), groupArn)) {
            assertNull(service.getResourcePolicy(REGION, ACCOUNT, arn));
            String initial = policy(arn, "Read");
            service.putResourcePolicy(REGION, ACCOUNT, arn, initial);
            assertEquals(initial, service.getResourcePolicy(REGION, ACCOUNT, arn));
            String replacement = policy(arn, "ReadAgain");
            service.putResourcePolicy(REGION, ACCOUNT, arn, replacement);
            assertEquals(replacement, service.getResourcePolicy(REGION, ACCOUNT, arn));
            service.deleteResourcePolicy(REGION, ACCOUNT, arn);
            service.deleteResourcePolicy(REGION, ACCOUNT, arn);
            assertNull(service.getResourcePolicy(REGION, ACCOUNT, arn));
        }
    }

    @Test
    void policyOperationsValidateOwnerRegionTypeAndExistence() {
        CodeBuildService service = service();
        String arn = createProject(service, "owned-project").getArn();
        String policy = policy(arn, "Read");
        service.putResourcePolicy(REGION, ACCOUNT, arn, policy);
        for (String invalidArn : List.of("owned-project", arn.replace("project/", "build/"),
                arn.replace(REGION, "us-west-2"), arn.replace(ACCOUNT, "111111111111"),
                arn.replace("arn:aws:", "arn:aws-cn:"))) {
            assertEquals("InvalidInputException", assertThrows(AwsException.class,
                    () -> service.getResourcePolicy(REGION, ACCOUNT, invalidArn)).getErrorCode());
            assertEquals("InvalidInputException", assertThrows(AwsException.class,
                    () -> service.putResourcePolicy(REGION, ACCOUNT, invalidArn, policy)).getErrorCode());
            assertEquals("InvalidInputException", assertThrows(AwsException.class,
                    () -> service.deleteResourcePolicy(REGION, ACCOUNT, invalidArn)).getErrorCode());
        }
        assertEquals("InvalidInputException", assertThrows(AwsException.class,
                () -> service.getResourcePolicy(REGION, "111111111111", arn)).getErrorCode());
        String missing = arn.replace("owned-project", "missing-project");
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                () -> service.getResourcePolicy(REGION, ACCOUNT, missing)).getErrorCode());
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                () -> service.putResourcePolicy(REGION, ACCOUNT, missing, policy)).getErrorCode());
        service.deleteResourcePolicy(REGION, ACCOUNT, missing);
        assertEquals(policy, service.getResourcePolicy(REGION, ACCOUNT, arn));
    }

    @Test
    void invalidPolicyDoesNotOverwriteExistingPolicy() {
        CodeBuildService service = service();
        String arn = createProject(service, "valid-policy-project").getArn();
        String policy = policy(arn, "Read");
        service.putResourcePolicy(REGION, ACCOUNT, arn, policy);
        for (String invalid : List.of("", "not-json", "null", "[]", "{}",
                "{\"Statement\":[]}", "{\"Statement\":[{}]}", policy + " {}")) {
            assertEquals("InvalidInputException", assertThrows(AwsException.class,
                    () -> service.putResourcePolicy(REGION, ACCOUNT, arn, invalid)).getErrorCode());
            assertEquals(policy, service.getResourcePolicy(REGION, ACCOUNT, arn));
        }
        assertEquals("InvalidInputException", assertThrows(AwsException.class,
                () -> service.putResourcePolicy(REGION, ACCOUNT, arn, null)).getErrorCode());
    }

    @Test
    void deletingResourcesIsIdempotentAndRemovesPoliciesBeforeRecreation() {
        CodeBuildService service = service();
        String projectArn = createProject(service, "recreated-project").getArn();
        String groupArn = service.createReportGroup(REGION, ACCOUNT, "recreated-reports", "TEST", null, null).getArn();
        service.putResourcePolicy(REGION, ACCOUNT, projectArn, policy(projectArn, "Read"));
        service.putResourcePolicy(REGION, ACCOUNT, groupArn, policy(groupArn, "Read"));
        service.deleteProject(REGION, "recreated-project");
        service.deleteProject(REGION, "recreated-project");
        service.deleteReportGroup(REGION, groupArn);
        service.deleteReportGroup(REGION, groupArn);
        assertTrue(service.batchGetProjects(REGION, List.of("recreated-project")).isEmpty());
        assertTrue(service.batchGetReportGroups(REGION, List.of(groupArn)).isEmpty());
        createProject(service, "recreated-project");
        service.createReportGroup(REGION, ACCOUNT, "recreated-reports", "TEST", null, null);
        assertNull(service.getResourcePolicy(REGION, ACCOUNT, projectArn));
        assertNull(service.getResourcePolicy(REGION, ACCOUNT, groupArn));
    }

    @Test
    void batchDeleteBuildsRemovesOnlyCompletedBuildsInTheRequestAccount() {
        CodeBuildService service = service();
        createProject(service, "delete-builds-project");
        Build completed = service.startBuild(REGION, ACCOUNT, "delete-builds-project", null,
                null, null, null, null, null, null);
        Build running = service.startBuild(REGION, ACCOUNT, "delete-builds-project", null,
                null, null, null, null, null, null);
        service.getBuild(REGION, ACCOUNT, completed.getId()).setBuildComplete(true);
        service.getBuild(REGION, ACCOUNT, completed.getId()).setBuildStatus("SUCCEEDED");
        assertTrue(service.batchDeleteBuilds(REGION, "111111111111", List.of(completed.getId()))
                .get("buildsDeleted").isEmpty());
        assertEquals(2, service.listBuildsForProject(REGION, ACCOUNT, "delete-builds-project").size());
        Map<String, List<?>> result = service.batchDeleteBuilds(REGION, ACCOUNT,
                List.of(completed.getArn(), running.getId(), "delete-builds-project:missing"));
        assertEquals(List.of(completed.getArn()), result.get("buildsDeleted"));
        assertEquals(List.of(
                Map.of("id", running.getId(), "statusCode", "BUILD_IN_PROGRESS"),
                Map.of("id", "delete-builds-project:missing", "statusCode", "RESOURCE_NOT_FOUND")),
                result.get("buildsNotDeleted"));
        assertEquals(List.of(running.getId()), service.listBuildsForProject(REGION, ACCOUNT, "delete-builds-project"));
        assertTrue(service.batchGetBuilds(REGION, ACCOUNT, List.of(completed.getId())).isEmpty());
        assertEquals("InvalidInputException", assertThrows(AwsException.class,
                () -> service.batchDeleteBuilds(REGION, ACCOUNT, List.of())).getErrorCode());
    }

    @Test
    void batchAndReportBindingsReturnStoredTruthAndTypedErrors() throws Exception {
        CodeBuildService service = service();
        createProject(service, "bindings-project");
        ObjectMapper mapper = new ObjectMapper();
        CodeBuildJsonHandler handler = new CodeBuildJsonHandler(service, mapper);
        String groupArn = service.createReportGroup(REGION, ACCOUNT, "bindings-reports", "TEST", null, null).getArn();
        String missingBatch = "bindings-project:00000000-0000-0000-0000-000000000000";
        String missingReport = groupArn.replace(":report-group/", ":report/") + ":missing";

        AwsException noConfig = assertThrows(AwsException.class, () -> handler.handle("StartBuildBatch",
                mapper.valueToTree(Map.of("projectName", "bindings-project")), REGION, ACCOUNT));
        assertEquals("InvalidInputException", noConfig.getErrorCode());
        assertTrue(noConfig.getMessage().contains("no build batch configuration"));
        service.configureBuildBatch(REGION, "bindings-project", Map.of("serviceRole", "batch-role"));
        AwsException unsupported = assertThrows(AwsException.class, () -> handler.handle("StartBuildBatch",
                mapper.valueToTree(Map.of("projectName", "bindings-project")), REGION, ACCOUNT));
        assertEquals("InvalidInputException", unsupported.getErrorCode());
        assertTrue(unsupported.getMessage().contains("not supported by Floci"));
        assertTrue(service.listBuilds(REGION, ACCOUNT).isEmpty());

        assertEquals(Map.of("ids", List.of()), handler.handle("ListBuildBatchesForProject",
                mapper.valueToTree(Map.of("projectName", "bindings-project")), REGION, ACCOUNT).getEntity());
        assertEquals(Map.of("buildBatches", List.of(), "buildBatchesNotFound", List.of(missingBatch)),
                handler.handle("BatchGetBuildBatches", mapper.valueToTree(Map.of("ids", List.of(missingBatch))),
                        REGION, ACCOUNT).getEntity());
        for (String action : List.of("StopBuildBatch", "RetryBuildBatch")) {
            assertEquals("ResourceNotFoundException", assertThrows(AwsException.class, () -> handler.handle(action,
                    mapper.valueToTree(Map.of("id", missingBatch)), REGION, ACCOUNT)).getErrorCode());
        }
        assertEquals(200, handler.handle("DeleteBuildBatch", mapper.valueToTree(Map.of("id", missingBatch)),
                REGION, ACCOUNT).getStatus());
        assertEquals(Map.of("reports", List.of()), handler.handle("ListReportsForReportGroup",
                mapper.valueToTree(Map.of("reportGroupArn", groupArn)), REGION, ACCOUNT).getEntity());
        assertEquals(Map.of("reports", List.of(), "reportsNotFound", List.of(missingReport)),
                handler.handle("BatchGetReports", mapper.valueToTree(Map.of("reportArns", List.of(missingReport))),
                        REGION, ACCOUNT).getEntity());
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class, () -> handler.handle(
                "DescribeTestCases", mapper.valueToTree(Map.of("reportArn", missingReport)), REGION, ACCOUNT))
                .getErrorCode());
        assertEquals("InvalidInputException", assertThrows(AwsException.class, () -> handler.handle(
                "DescribeCodeCoverages", mapper.valueToTree(Map.of("reportArn", missingReport)), REGION, ACCOUNT))
                .getErrorCode());
        assertEquals(Map.of("rawData", List.of()), handler.handle("GetReportGroupTrend",
                mapper.valueToTree(Map.of("reportGroupArn", groupArn, "trendField", "DURATION")),
                REGION, ACCOUNT).getEntity());
        assertEquals(200, handler.handle("DeleteReport", mapper.valueToTree(Map.of("arn", missingReport)),
                REGION, ACCOUNT).getStatus());
        assertEquals("InvalidInputException", assertThrows(AwsException.class, () -> handler.handle("StartSandbox",
                mapper.valueToTree(Map.of("projectName", "bindings-project")), REGION, ACCOUNT)).getErrorCode());
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class, () -> service
                .listBuildBatchesForProject(REGION, "111111111111",
                        mapper.valueToTree(Map.of("projectName", "bindings-project")))).getErrorCode());
        assertEquals("InvalidInputException", assertThrows(AwsException.class, () -> service
                .batchGetBuildBatches(REGION, ACCOUNT, List.of())).getErrorCode());
    }

    private CodeBuildService service() {
        return new CodeBuildService(mock(CodeBuildRunner.class), mock(EmulatorConfig.class), null, new ObjectMapper());
    }

    private Project createProject(CodeBuildService service, String name) {
        ProjectSource source = new ProjectSource();
        source.setType("NO_SOURCE");
        ProjectArtifacts artifacts = new ProjectArtifacts();
        artifacts.setType("NO_ARTIFACTS");
        return service.createProject(REGION, ACCOUNT, name, null, source, null, null,
                artifacts, null, new ProjectEnvironment(), "arn:aws:iam::" + ACCOUNT + ":role/codebuild",
                null, null, null, null, null, null, null);
    }

    private String policy(String arn, String sid) {
        String action = arn.contains(":report-group/") ? "BatchGetReportGroups" : "BatchGetProjects";
        return """
                {"Version":"2012-10-17","Statement":[{"Sid":"%s","Effect":"Allow",
                "Principal":{"AWS":"arn:aws:iam::000000000000:root"},
                "Action":"codebuild:%s","Resource":"%s"}]}
                """.formatted(sid, action, arn);
    }

    @Test
    void retryBuildRetainsOriginalBuildspecOverride() {
        CodeBuildRunner runner = mock(CodeBuildRunner.class);
        CodeBuildService service = new CodeBuildService(runner, mock(EmulatorConfig.class), null, new ObjectMapper());
        ProjectSource source = new ProjectSource();
        source.setType("NO_SOURCE");
        ProjectArtifacts artifacts = new ProjectArtifacts();
        artifacts.setType("NO_ARTIFACTS");

        service.createProject(REGION, ACCOUNT, "retry-project", null, source, null, null,
                artifacts, null, new ProjectEnvironment(), "arn:aws:iam::000000000000:role/codebuild",
                null, null, null, null, null, null, null);

        String buildspec = "version: 0.2\nphases:\n  build:\n    commands:\n      - echo retry\n";
        Build original = service.startBuild(REGION, ACCOUNT, "retry-project", buildspec,
                null, null, null, null, null, null);
        Build retried = service.retryBuild(REGION, ACCOUNT, original.getId());

        assertNotEquals(original.getId(), retried.getId());
        ArgumentCaptor<String> buildspecOverrides = ArgumentCaptor.forClass(String.class);
        verify(runner, times(2)).startBuild(eq(REGION), any(Build.class), any(Project.class),
                buildspecOverrides.capture());
        assertEquals(List.of(buildspec, buildspec), buildspecOverrides.getAllValues());
    }
}
