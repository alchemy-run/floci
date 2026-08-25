package io.github.hectorvent.floci.services.memorydb;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.elasticache.proxy.SigV4Validator;
import io.github.hectorvent.floci.services.memorydb.container.MemoryDbContainerManager;
import io.github.hectorvent.floci.services.memorydb.model.ParameterGroup;
import io.github.hectorvent.floci.services.memorydb.proxy.MemoryDbProxyManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MemoryDbParameterGroupServiceTest {

    private MemoryDbService service;

    @BeforeEach
    void setUp() {
        StorageFactory storageFactory = mock(StorageFactory.class);
        EmulatorConfig config = mock(EmulatorConfig.class);
        RegionResolver regionResolver = mock(RegionResolver.class);
        EmulatorConfig.ServicesConfig servicesConfig = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.MemoryDbServiceConfig mdbConfig = mock(EmulatorConfig.MemoryDbServiceConfig.class);
        when(config.services()).thenReturn(servicesConfig);
        when(servicesConfig.memorydb()).thenReturn(mdbConfig);
        when(mdbConfig.proxyBasePort()).thenReturn(16400);
        when(mdbConfig.proxyMaxPort()).thenReturn(16419);
        when(mdbConfig.defaultImage()).thenReturn("valkey/valkey:8");
        when(config.hostname()).thenReturn(Optional.of("localhost"));
        when(regionResolver.getAccountId()).thenReturn("000000000000");
        when(regionResolver.buildArn(anyString(), anyString(), anyString())).thenAnswer(inv ->
                AwsArnUtils.Arn.of(inv.getArgument(0), inv.getArgument(1),
                        "000000000000", inv.getArgument(2)).toString());
        when(storageFactory.create(anyString(), anyString(), any())).thenAnswer(inv -> new InMemoryStorage<>());

        service = new MemoryDbService(
                mock(MemoryDbContainerManager.class),
                mock(MemoryDbProxyManager.class),
                mock(SigV4Validator.class),
                storageFactory, config, regionResolver);
    }

    @Test
    void describeMissingGroupThrowsParameterGroupNotFoundFault() {
        AwsException ex = assertThrows(AwsException.class,
                () -> service.describeParameterGroups("missing-group"));
        assertEquals("ParameterGroupNotFoundFault", ex.getErrorCode());
        assertEquals(404, ex.getHttpStatus());
    }

    @Test
    void createUpdateResetDeleteParameterGroup() {
        ParameterGroup spec = new ParameterGroup();
        spec.setName("pg1");
        spec.setFamily("memorydb_valkey7");
        spec.setDescription("desc");
        spec.setTags(Map.of("fixture", "memorydb-parameter-group"));

        ParameterGroup created = service.createParameterGroup(spec, "us-east-1");
        assertEquals("pg1", created.getName());
        assertEquals("memorydb_valkey7", created.getFamily());
        assertEquals("arn:aws:memorydb:us-east-1:000000000000:parametergroup/pg1", created.getArn());
        assertEquals("noeviction", service.describeParameters("pg1").get("maxmemory-policy"));

        service.updateParameterGroup("pg1", Map.of("maxmemory-policy", "allkeys-lru"));
        assertEquals("allkeys-lru", service.describeParameters("pg1").get("maxmemory-policy"));

        service.resetParameterGroup("pg1", false, List.of("maxmemory-policy"));
        assertEquals("noeviction", service.describeParameters("pg1").get("maxmemory-policy"));

        assertEquals("memorydb-parameter-group", service.listTags(created.getArn()).get("fixture"));
        service.tagResource(created.getArn(), Map.of("env", "dev"));
        assertEquals("dev", service.listTags(created.getArn()).get("env"));
        service.untagResource(created.getArn(), List.of("env"));

        service.deleteParameterGroup("pg1");
        AwsException gone = assertThrows(AwsException.class,
                () -> service.describeParameterGroups("pg1"));
        assertEquals("ParameterGroupNotFoundFault", gone.getErrorCode());
    }

    @Test
    void duplicateGroupRejected() {
        ParameterGroup spec = new ParameterGroup();
        spec.setName("pg1");
        spec.setFamily("memorydb_valkey7");
        service.createParameterGroup(spec, "us-east-1");
        AwsException ex = assertThrows(AwsException.class,
                () -> service.createParameterGroup(spec, "us-east-1"));
        assertEquals("ParameterGroupAlreadyExistsFault", ex.getErrorCode());
    }
}
