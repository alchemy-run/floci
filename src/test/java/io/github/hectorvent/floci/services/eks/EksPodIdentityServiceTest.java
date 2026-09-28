package io.github.hectorvent.floci.services.eks;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.ServiceConfigAccess;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.eks.model.Cluster;
import io.github.hectorvent.floci.services.eks.model.ClusterStatus;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EksPodIdentityServiceTest {
    private static final String ACCOUNT = "123456789012";
    private static final String ROLE = "arn:aws:iam::" + ACCOUNT + ":role/path/pods";
    private static final String TARGET = "arn:aws:iam::999999999999:role/target";
    private static final String POLICY = "{\"Version\":\"2012-10-17\",\"Statement\":[]}";

    @Test
    void createUpdateTagsAndDeleteSurviveStorageFactoryReloads(@TempDir Path directory) {
        Cluster cluster = cluster();
        StorageFactory firstStore = persistentFactory(directory);
        EksPodIdentityService first = new EksPodIdentityService(firstStore, iam(), new ObjectMapper());
        EksPodIdentityService.CreateRequest request = request("api", "create");
        EksPodIdentityService.Association created = first.create(cluster, request);
        firstStore.flushAll();

        StorageFactory secondStore = persistentFactory(directory);
        EksPodIdentityService second = new EksPodIdentityService(secondStore, iam(), new ObjectMapper());
        assertEquals(created, second.describe(cluster, created.associationId()));
        assertEquals(created, second.create(cluster, request));
        EksPodIdentityService.UpdateRequest update = new EksPodIdentityService.UpdateRequest(
                ROLE, true, TARGET, POLICY, "update");
        EksPodIdentityService.Association updated = second.update(cluster, created.associationId(), update);
        second.tag(cluster, created.associationId(), Map.of("owner", "platform"), List.of("environment"));
        secondStore.flushAll();

        StorageFactory thirdStore = persistentFactory(directory);
        EksPodIdentityService third = new EksPodIdentityService(thirdStore, iam(), new ObjectMapper());
        EksPodIdentityService.Association restored = third.describe(cluster, created.associationId());
        assertEquals(created.associationArn(), restored.associationArn());
        assertEquals(created.createdAt(), restored.createdAt());
        assertTrue(restored.disableSessionTags());
        assertEquals(TARGET, restored.targetRoleArn());
        assertEquals(POLICY, restored.policy());
        assertEquals(Map.of("owner", "platform"), restored.tags());
        assertEquals(updated, third.update(cluster, created.associationId(), update));
        assertEquals(created, third.create(cluster, request));
        assertEquals(restored, third.delete(cluster, created.associationId()));
        thirdStore.flushAll();

        StorageFactory fourthStore = persistentFactory(directory);
        EksPodIdentityService fourth = new EksPodIdentityService(fourthStore, iam(), new ObjectMapper());
        assertTrue(fourth.list(cluster, null, null, null, null).associations().isEmpty());
        assertError("ResourceNotFoundException", () -> fourth.describe(cluster, created.associationId()));
        assertError("ResourceNotFoundException", () -> fourth.delete(cluster, created.associationId()));
    }

    @Test
    void listPaginationAndTokensAreBoundToClusterGenerationAccountRegionAndFilters() {
        EksPodIdentityService service = service();
        Cluster cluster = cluster();
        EksPodIdentityService.Association first = service.create(cluster, request("api", "one"));
        service.create(cluster, request("worker", "two"));
        EksPodIdentityService.Page page = service.list(cluster, "default", null, 1, null);
        assertEquals(1, page.associations().size());
        assertNotNull(page.nextToken());
        EksPodIdentityService.Page last = service.list(cluster, "default", null, 1, page.nextToken());
        assertEquals(1, last.associations().size());
        assertNotEquals(page.associations().getFirst().associationId(), last.associations().getFirst().associationId());
        assertNull(last.nextToken());
        assertEquals(first.associationId(), service.list(cluster, "default", "api", 100, null)
                .associations().getFirst().associationId());
        assertError("InvalidParameterException", () -> service.list(cluster, "other", null, 1, page.nextToken()));
        for (String arn : List.of(cluster.getArn().replace(ACCOUNT, "999999999999"),
                cluster.getArn().replace("us-east-1", "eu-west-1"))) {
            Cluster foreign = cluster();
            foreign.setArn(arn);
            assertTrue(service.list(foreign, null, null, null, null).associations().isEmpty());
            assertError("ResourceNotFoundException", () -> service.describe(foreign, first.associationId()));
            assertError("ResourceNotFoundException", () -> service.delete(foreign, first.associationId()));
            assertError("InvalidParameterException", () -> service.list(foreign, "default", null, 1, page.nextToken()));
        }
        Cluster recreated = cluster();
        recreated.setCreatedAt(cluster.getCreatedAt().plusSeconds(1));
        assertTrue(service.list(recreated, null, null, null, null).associations().isEmpty());
        assertError("InvalidParameterException", () -> service.list(recreated, "default", null, 1, page.nextToken()));
        assertEquals(first, service.describe(cluster, first.associationId()));
        service.deleteClusterAssociations(cluster);
        assertTrue(service.list(cluster, null, null, null, null).associations().isEmpty());
    }

    @Test
    void duplicatesAndIdempotencyConflictsUseTypedErrorsWithoutMutatingState() {
        EksPodIdentityService service = service();
        Cluster cluster = cluster();
        EksPodIdentityService.Association first = service.create(cluster, request("api", "one"));
        assertError("ResourceInUseException", () -> service.create(cluster, request("api", "two")));
        assertError("InvalidParameterException", () -> service.create(cluster, request("other", "one")));
        service.update(cluster, first.associationId(), new EksPodIdentityService.UpdateRequest(null, true, null, null, "update"));
        assertError("InvalidParameterException", () -> service.update(cluster, first.associationId(),
                new EksPodIdentityService.UpdateRequest(null, false, null, null, "update")));
        assertTrue(service.describe(cluster, first.associationId()).disableSessionTags());
        assertEquals(1, service.list(cluster, null, null, null, null).associations().size());
    }

    @Test
    void partialUpdatesPreserveOmittedFieldsAndCanExplicitlyRestoreSessionTags() {
        IamService iam = iam();
        String replacementRole = ROLE.replace("pods", "replacement");
        when(iam.findRole(ACCOUNT, "replacement")).thenReturn(Optional.of(
                new IamRole("AROA-replacement", "replacement", "/path/", replacementRole, "{}")));
        EksPodIdentityService service = new EksPodIdentityService(new InMemoryStorage<>(), iam, new ObjectMapper());
        Cluster cluster = cluster();
        EksPodIdentityService.Association original = service.create(cluster, request("api.service", "create"));
        service.update(cluster, original.associationId(), new EksPodIdentityService.UpdateRequest(
                replacementRole, true, TARGET, POLICY, "first"));
        EksPodIdentityService.Association updated = service.update(cluster, original.associationId(),
                new EksPodIdentityService.UpdateRequest(null, false, null, null, "second"));
        assertFalse(updated.disableSessionTags());
        assertEquals(replacementRole, updated.roleArn());
        assertEquals(TARGET, updated.targetRoleArn());
        assertEquals(POLICY, updated.policy());
        assertEquals(original.associationArn(), updated.associationArn());
        assertError("InvalidParameterException", () -> service.update(cluster, original.associationId(),
                new EksPodIdentityService.UpdateRequest(null, null, null, null, "empty")));
    }

    @Test
    void invalidRolesNamesPolicyPaginationAndInactiveClusterAreRejected() {
        EksPodIdentityService service = service();
        Cluster cluster = cluster();
        assertError("InvalidParameterException", () -> service.create(cluster,
                new EksPodIdentityService.CreateRequest("default", "api", ROLE.replace(ACCOUNT, "999999999999"),
                        null, null, null, null, null)));
        assertError("InvalidParameterException", () -> service.create(cluster,
                new EksPodIdentityService.CreateRequest("default", "api", ROLE.replace("pods", "missing"),
                        null, null, null, null, null)));
        assertError("InvalidParameterException", () -> service.create(cluster, request("INVALID", null)));
        assertError("InvalidParameterException", () -> service.create(cluster,
                new EksPodIdentityService.CreateRequest("default", "api", ROLE, null, null, "not-json", null, null)));
        for (int limit : List.of(0, -1, 101)) {
            assertError("InvalidParameterException", () -> service.list(cluster, null, null, limit, null));
        }
        assertError("InvalidParameterException", () -> service.list(cluster, null, null, 1, "bad"));
        cluster.setStatus(ClusterStatus.CREATING);
        assertError("InvalidRequestException", () -> service.create(cluster, request("api", null)));
    }

    private static EksPodIdentityService.CreateRequest request(String serviceAccount, String token) {
        return new EksPodIdentityService.CreateRequest("default", serviceAccount, ROLE, null, null, null,
                Map.of("environment", "test"), token);
    }

    private static Cluster cluster() {
        Cluster cluster = new Cluster();
        cluster.setName("pod-identities");
        cluster.setArn("arn:aws:eks:us-east-1:" + ACCOUNT + ":cluster/pod-identities");
        cluster.setCreatedAt(Instant.parse("2026-09-21T00:00:00Z"));
        cluster.setStatus(ClusterStatus.ACTIVE);
        return cluster;
    }

    private static IamService iam() {
        IamService iam = mock(IamService.class);
        when(iam.findRole(ACCOUNT, "pods")).thenReturn(Optional.of(new IamRole("AROA-pods", "pods", "/path/", ROLE, "{}")));
        return iam;
    }

    private static EksPodIdentityService service() {
        return new EksPodIdentityService(new InMemoryStorage<>(), iam(), new ObjectMapper());
    }

    private static StorageFactory persistentFactory(Path directory) {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.StorageConfig storage = mock(EmulatorConfig.StorageConfig.class);
        ServiceConfigAccess access = mock(ServiceConfigAccess.class);
        when(config.defaultAccountId()).thenReturn(ACCOUNT);
        when(config.storage()).thenReturn(storage);
        when(storage.persistentPath()).thenReturn(directory.toString());
        when(access.storageMode("eks")).thenReturn("persistent");
        return new StorageFactory(config, access);
    }

    private static void assertError(String code, Executable action) {
        assertEquals(code, assertThrows(AwsException.class, action).getErrorCode());
    }
}
