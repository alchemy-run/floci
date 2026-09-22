package io.github.hectorvent.floci.services.codedeploy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.codedeploy.model.DeploymentGroup;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ecs.EcsService;
import io.github.hectorvent.floci.services.elbv2.ElbV2Service;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.ssm.SsmCommandService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Verifies CodeDeploy durable resources survive a restart, including the nested deployment groups
 * and the built-in deployment configs. Two service instances share the same {@link StorageFactory}
 * backends; the second simulates a process restart reloading from disk.
 */
class CodeDeployServicePersistenceTest {

    private static final String REGION = "us-east-1";

    @Test
    void durableResourcesAndBuiltInsSurviveRestart() {
        SharedStorageFactory storage = new SharedStorageFactory();

        CodeDeployService first = serviceWithStorage(storage);
        first.createApplication(REGION, "web-app", "Server",
                List.of(Map.of("Key", "team", "Value", "platform")));
        first.createDeploymentGroup(REGION, "web-app", "prod-group",
                "CodeDeployDefault.OneAtATime", "arn:aws:iam::000000000000:role/cd", null);
        first.createDeploymentConfig(REGION, "custom-cfg",
                Map.of("type", "HOST_COUNT", "value", 1), "Server", null, null);
        first.registerOnPremisesInstance(REGION, "onprem-1",
                "arn:aws:sts::000000000000:session/s", "arn:aws:iam::000000000000:user/u");
        first.tagResource(first.applicationArn(REGION, "web-app"),
                List.of(Map.of("Key", "env", "Value", "prod")));

        CodeDeployService reloaded = serviceWithStorage(storage);

        assertEquals(List.of("web-app"), reloaded.listApplications(REGION));
        DeploymentGroup group = reloaded.getDeploymentGroup(REGION, "web-app", "prod-group");
        assertEquals("CodeDeployDefault.OneAtATime", group.getDeploymentConfigName());
        assertNotNull(reloaded.getDeploymentConfig(REGION, "custom-cfg"));
        // built-in configs survive (they are persisted, and topped up on load)
        assertNotNull(reloaded.getDeploymentConfig(REGION, "CodeDeployDefault.OneAtATime"));
        assertTrue(reloaded.listDeploymentConfigs(REGION).size() >= 18,
                "17 built-ins + custom config expected after restart");
        assertEquals("Registered",
                reloaded.getOnPremisesInstance(REGION, "onprem-1").getRegistrationStatus());
        Map<String, String> appTags = reloaded.listTagsForResource(first.applicationArn(REGION, "web-app"))
                .stream().collect(java.util.stream.Collectors.toMap(t -> t.get("Key"), t -> t.get("Value")));
        assertEquals("prod", appTags.get("env"));   // tagResource
        assertEquals("platform", appTags.get("team")); // createApplication tags
    }

    @Test
    void deletesAndUntagArePersistedAfterRestart() {
        SharedStorageFactory storage = new SharedStorageFactory();

        CodeDeployService first = serviceWithStorage(storage);
        first.createApplication(REGION, "keep-app", "Server", null);
        first.createApplication(REGION, "drop-app", "Server", null);
        first.createDeploymentGroup(REGION, "keep-app", "g1", null, "arn:r", null);
        first.createDeploymentGroup(REGION, "keep-app", "g2", null, "arn:r", null);
        first.deleteApplication(REGION, "drop-app");
        first.deleteDeploymentGroup(REGION, "keep-app", "g2");
        String arn = first.applicationArn(REGION, "keep-app");
        first.tagResource(arn, List.of(Map.of("Key", "env", "Value", "prod"),
                Map.of("Key", "team", "Value", "sec")));
        first.untagResource(arn, List.of("team"));

        CodeDeployService reloaded = serviceWithStorage(storage);

        assertEquals(List.of("keep-app"), reloaded.listApplications(REGION));
        assertEquals(List.of("g1"), reloaded.listDeploymentGroups(REGION, "keep-app"));
        assertThrows(Exception.class, () -> reloaded.getDeploymentGroup(REGION, "keep-app", "g2"));
        Map<String, String> tags = reloaded.listTagsForResource(arn).stream()
                .collect(java.util.stream.Collectors.toMap(t -> t.get("Key"), t -> t.get("Value")));
        assertEquals("prod", tags.get("env"));
        assertTrue(!tags.containsKey("team"), "untagged key must not reappear after restart");
    }

    @Test
    void revisionsSurviveRestartAndRenameButNotApplicationDeletion() {
        SharedStorageFactory storage = new SharedStorageFactory();
        CodeDeployService first = serviceWithStorage(storage);
        first.createApplication(REGION, "revision-app", "Lambda", null);
        Map<String, Object> revision = Map.of("revisionType", "S3", "s3Location",
                Map.of("bucket", "bundle-bucket", "key", "release.zip", "bundleType", "zip", "version", "v1"));
        first.registerApplicationRevision(REGION, "revision-app", revision, "persisted metadata");
        first.updateApplication(REGION, "revision-app", "renamed-app");

        CodeDeployService reloaded = serviceWithStorage(storage);
        Map<String, Object> got = reloaded.getApplicationRevision(REGION, "renamed-app", revision);
        assertEquals("renamed-app", got.get("applicationName"));
        assertEquals(revision, got.get("revision"));
        assertEquals("persisted metadata", ((Map<?, ?>) got.get("revisionInfo")).get("description"));
        assertEquals(List.of(revision), reloaded.listApplicationRevisions(REGION, "renamed-app",
                null, null, null, null, null, null).get("revisions"));

        reloaded.deleteApplication(REGION, "renamed-app");
        CodeDeployService afterDelete = serviceWithStorage(storage);
        afterDelete.createApplication(REGION, "renamed-app", "Lambda", null);
        assertEquals(List.of(), afterDelete.listApplicationRevisions(REGION, "renamed-app",
                null, null, null, null, null, null).get("revisions"));
        assertEquals("RevisionDoesNotExistException", assertThrows(AwsException.class,
                () -> afterDelete.getApplicationRevision(REGION, "renamed-app", revision)).getErrorCode());
    }

    @Test
    void revisionIdentityUsesLocationValuesAndAcceptsAllWireLocationTypes() {
        CodeDeployService service = serviceWithStorage(new SharedStorageFactory());
        service.createApplication(REGION, "revision-types", "Server", null);
        List<Map<String, Object>> revisions = List.of(
                Map.of("revisionType", "S3", "s3Location", Map.of("bucket", "bucket", "key", "a.zip", "bundleType", "zip", "version", "1")),
                Map.of("revisionType", "S3", "s3Location", Map.of("bucket", "bucket", "key", "a.zip", "bundleType", "zip", "version", "2")),
                Map.of("revisionType", "GitHub", "gitHubLocation", Map.of("repository", "owner/repo", "commitId", "abc123")),
                Map.of("revisionType", "String", "string", Map.of("content", "version: 0.0", "sha256", "abc")),
                Map.of("revisionType", "AppSpecContent", "appSpecContent", Map.of("content", "version: 0.0")));
        for (Map<String, Object> revision : revisions) {
            service.registerApplicationRevision(REGION, "revision-types", revision, "registered");
            assertEquals(revision, service.getApplicationRevision(REGION, "revision-types", revision).get("revision"));
        }
        Map<String, Object> reordered = new LinkedHashMap<>();
        reordered.put("s3Location", revisions.getFirst().get("s3Location"));
        reordered.put("revisionType", "S3");
        service.registerApplicationRevision(REGION, "revision-types", reordered, "updated");
        List<?> listed = (List<?>) service.listApplicationRevisions(REGION, "revision-types",
                null, null, null, null, null, null).get("revisions");
        assertEquals(revisions.size(), listed.size());
        assertEquals(new HashSet<>(revisions), new HashSet<>(listed));
        assertEquals("updated", ((Map<?, ?>) service.getApplicationRevision(REGION, "revision-types", reordered)
                .get("revisionInfo")).get("description"));
        assertEquals(List.of(), service.listApplicationRevisions(REGION, "revision-types",
                null, null, "other-bucket", null, null, null).get("revisions"));
    }

    @Test
    void revisionPaginationIsBoundToApplicationAndFiltersAndBatchLimitIsEnforced() {
        CodeDeployService service = serviceWithStorage(new SharedStorageFactory());
        service.createApplication(REGION, "paged-revisions", "Server", null);
        service.createApplication(REGION, "other-revisions", "Server", null);
        List<Map<String, Object>> revisions = new ArrayList<>();
        for (int index = 0; index < 101; index++) {
            Map<String, Object> revision = Map.of("revisionType", "S3", "s3Location",
                    Map.of("bucket", "bucket", "key", "release-" + index + ".zip", "bundleType", "zip"));
            revisions.add(revision);
            service.registerApplicationRevision(REGION, "paged-revisions", revision, null);
        }
        Map<String, Object> first = service.listApplicationRevisions(REGION, "paged-revisions",
                null, null, null, null, null, null);
        assertEquals(revisions.subList(0, 100), first.get("revisions"));
        String token = (String) first.get("nextToken");
        assertNotNull(token);
        Map<String, Object> second = service.listApplicationRevisions(REGION, "paged-revisions",
                null, null, null, null, null, token);
        assertEquals(List.of(revisions.getLast()), second.get("revisions"));
        assertEquals(false, second.containsKey("nextToken"));
        assertEquals("InvalidNextTokenException", assertThrows(AwsException.class,
                () -> service.listApplicationRevisions(REGION, "other-revisions",
                        null, null, null, null, null, token)).getErrorCode());
        assertEquals("InvalidNextTokenException", assertThrows(AwsException.class,
                () -> service.listApplicationRevisions(REGION, "paged-revisions",
                        null, null, "bucket", null, null, token)).getErrorCode());
        assertEquals("BatchLimitExceededException", assertThrows(AwsException.class,
                () -> service.batchGetApplicationRevisions(REGION, "paged-revisions", revisions.subList(0, 26))).getErrorCode());
    }

    private static CodeDeployService serviceWithStorage(StorageFactory storage) {
        CodeDeployService service = new CodeDeployService(
                mock(LambdaService.class), mock(EcsService.class), mock(ElbV2Service.class),
                mock(SsmCommandService.class), mock(Ec2Service.class), new ObjectMapper(),
                new RegionResolver(REGION, "000000000000"), storage);
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
