package io.github.hectorvent.floci.services.memorydb;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.elasticache.proxy.SigV4Validator;
import io.github.hectorvent.floci.services.memorydb.container.MemoryDbContainerManager;
import io.github.hectorvent.floci.services.memorydb.model.SubnetGroup;
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

class MemoryDbSubnetGroupServiceTest {

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
        when(regionResolver.getDefaultRegion()).thenReturn("us-east-1");
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
    void describeMissingGroupThrowsSubnetGroupNotFoundFault() {
        AwsException ex = assertThrows(AwsException.class,
                () -> service.describeSubnetGroups("missing-group"));
        assertEquals("SubnetGroupNotFoundFault", ex.getErrorCode());
        assertEquals(404, ex.getHttpStatus());
    }

    @Test
    void createUpdateDeleteSubnetGroup() {
        SubnetGroup spec = new SubnetGroup();
        spec.setName("sg1");
        spec.setDescription("alchemy memorydb subnet group");
        spec.setSubnets(List.of(
                new SubnetGroup.SubnetRef("subnet-aaa", null),
                new SubnetGroup.SubnetRef("subnet-bbb", null)));
        spec.setTags(Map.of("fixture", "memorydb-subnet-group"));

        SubnetGroup created = service.createSubnetGroup(spec, "us-east-1");
        assertEquals("sg1", created.getName());
        assertEquals("alchemy memorydb subnet group", created.getDescription());
        assertEquals("arn:aws:memorydb:us-east-1:000000000000:subnetgroup/sg1", created.getArn());
        assertEquals("vpc-floci", created.getVpcId());
        assertEquals(2, created.getSubnets().size());

        SubnetGroup updated = service.updateSubnetGroup("sg1",
                "alchemy memorydb subnet group v2", null, "us-east-1");
        assertEquals("alchemy memorydb subnet group v2", updated.getDescription());
        assertEquals(2, updated.getSubnets().size());

        assertEquals("memorydb-subnet-group", service.listTags(created.getArn()).get("fixture"));
        service.tagResource(created.getArn(), Map.of("env", "test"));
        assertEquals("test", service.listTags(created.getArn()).get("env"));
        service.untagResource(created.getArn(), List.of("env"));

        service.deleteSubnetGroup("sg1");
        AwsException gone = assertThrows(AwsException.class,
                () -> service.describeSubnetGroups("sg1"));
        assertEquals("SubnetGroupNotFoundFault", gone.getErrorCode());
    }

    @Test
    void duplicateGroupRejected() {
        SubnetGroup spec = new SubnetGroup();
        spec.setName("sg1");
        spec.setSubnets(List.of(new SubnetGroup.SubnetRef("subnet-aaa", null)));
        service.createSubnetGroup(spec, "us-east-1");
        AwsException ex = assertThrows(AwsException.class,
                () -> service.createSubnetGroup(spec, "us-east-1"));
        assertEquals("SubnetGroupAlreadyExistsFault", ex.getErrorCode());
    }
}
