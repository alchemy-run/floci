package io.github.hectorvent.floci.services.memorydb;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.elasticache.proxy.SigV4Validator;
import io.github.hectorvent.floci.services.memorydb.container.MemoryDbContainerManager;
import io.github.hectorvent.floci.services.memorydb.model.AuthMode;
import io.github.hectorvent.floci.services.memorydb.model.User;
import io.github.hectorvent.floci.services.memorydb.proxy.MemoryDbProxyManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MemoryDbUserServiceTest {

    private MemoryDbService service;

    @BeforeEach
    void setUp() {
        MemoryDbContainerManager containerManager = mock(MemoryDbContainerManager.class);
        MemoryDbProxyManager proxyManager = mock(MemoryDbProxyManager.class);
        SigV4Validator sigV4Validator = mock(SigV4Validator.class);
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

        service = new MemoryDbService(containerManager, proxyManager, sigV4Validator,
                storageFactory, config, regionResolver);
    }

    @Test
    void updateUserChangesAccessString() {
        User spec = passwordUser("app-user", "on ~* +@all");
        spec.setTags(Map.of("fixture", "memorydb-user"));
        User created = service.createUser(spec, "us-east-1");

        User updated = service.updateUser(created.getName(), "on ~app:* +@read", null, List.of());
        assertEquals("on ~app:* +@read", updated.getAccessString());
        assertEquals("active", updated.getStatus());
        assertEquals("password", updated.getAuthMode().wireValue());
    }

    @Test
    void updateUserMissingThrowsUserNotFoundFault() {
        AwsException ex = assertThrows(AwsException.class,
                () -> service.updateUser("missing-user", "on ~* +@all", null, List.of()));
        assertEquals("UserNotFoundFault", ex.jsonType());
        assertEquals(404, ex.getHttpStatus());
    }

    @Test
    void tagAndUntagUser() {
        User created = service.createUser(passwordUser("tagged-user", "on ~* +@all"), "us-east-1");
        String arn = created.getArn();

        service.tagResource(arn, Map.of("env", "test"));
        assertEquals("test", service.listTags(arn).get("env"));

        service.untagResource(arn, List.of("env"));
        assertFalse(service.listTags(arn).containsKey("env"));
    }

    private static User passwordUser(String name, String accessString) {
        User spec = new User();
        spec.setName(name);
        spec.setAuthMode(AuthMode.PASSWORD);
        spec.setPasswords(List.of("AlchemyMemoryDbTestPass01"));
        spec.setAccessString(accessString);
        return spec;
    }
}
