package io.github.hectorvent.floci.services.glue;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.core.common.ServiceConfigAccess;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GlueCatalogStorageTest {

    private static final String ACCOUNT = "111122223333";
    private static final String OTHER_ACCOUNT = "444455556666";
    private static final String DEFAULT_REGION = "eu-west-1";
    private static final String OTHER_REGION = "us-west-2";

    private final RequestContext context = new RequestContext();
    private final RegionResolver resolver = new RegionResolver(DEFAULT_REGION, ACCOUNT) {
        @Override
        public String getRegion() {
            return context.getRegion() == null ? getDefaultRegion() : context.getRegion();
        }
    };

    @Test
    void logicalKeysScansAndClearsStayWithinAccountAndRegion() {
        StorageBackend<String, String> raw = new InMemoryStorage<>();
        GlueCatalogStorage<String> store = new GlueCatalogStorage<>(accounts(raw), resolver);
        context.setAccountId(ACCOUNT);
        store.put("db:table", "default");
        context.setRegion(OTHER_REGION);
        assertTrue(store.get("db:table").isEmpty());
        assertTrue(store.scan(key -> true).isEmpty());
        store.put("db:table", "regional");
        store.put("other:table", "other");
        assertEquals(Set.of("db:table", "other:table"), store.keys());
        List<String> matches = store.scan(key -> key.startsWith("db:"));
        assertEquals(List.of("regional"), matches);
        matches.clear();
        assertEquals("regional", store.get("db:table").orElseThrow());

        context.setAccountId(OTHER_ACCOUNT);
        assertTrue(store.keys().isEmpty());
        store.put("db:table", "foreign");
        context.setAccountId(ACCOUNT);
        store.clear();
        assertTrue(store.keys().isEmpty());
        context.setRegion(DEFAULT_REGION);
        assertEquals(Set.of("db:table"), store.keys());
        assertEquals(List.of("default"), store.scan(key -> true));
        store.delete("db:table");
        context.setAccountId(OTHER_ACCOUNT);
        context.setRegion(OTHER_REGION);
        assertEquals("foreign", store.get("db:table").orElseThrow());
    }

    @Test
    void legacyKeysBelongToDefaultAccountAndRegionEvenWhenAnotherScopeReadsFirst() {
        StorageBackend<String, String> raw = new InMemoryStorage<>();
        raw.put("legacy/table", "pre-account");
        raw.put(ACCOUNT + "/db:table", "account-prefixed");
        raw.put("db:table", "stale-pre-account");
        context.setAccountId(OTHER_ACCOUNT);
        context.setRegion(OTHER_REGION);
        GlueCatalogStorage<String> store = new GlueCatalogStorage<>(accounts(raw), resolver);
        assertTrue(store.get("legacy/table").isEmpty());
        assertTrue(store.scan(key -> true).isEmpty());
        context.setRegion(DEFAULT_REGION);
        assertTrue(store.get("legacy/table").isEmpty());
        assertTrue(store.get("db:table").isEmpty());
        context.setAccountId(ACCOUNT);
        assertEquals(Set.of("legacy/table", "db:table"), store.keys());
        assertEquals("pre-account", store.get("legacy/table").orElseThrow());
        assertEquals("account-prefixed", store.get("db:table").orElseThrow());
        assertTrue(raw.get("legacy/table").isEmpty());
        assertTrue(raw.get("db:table").isEmpty());
        context.setRegion(OTHER_REGION);
        store.delete("legacy/table");
        store.put("db:table", "regional");
        context.setRegion(DEFAULT_REGION);
        assertEquals("pre-account", store.get("legacy/table").orElseThrow());
        assertEquals("account-prefixed", store.get("db:table").orElseThrow());
    }

    @Test
    void explicitRegionKeysSurviveDefaultRegionChangesAndWinLegacyCollisions() {
        StorageBackend<String, String> raw = new InMemoryStorage<>();
        AccountAwareStorageBackend<String> backend = accounts(raw);
        context.setAccountId(ACCOUNT);
        backend.put("db:table", "legacy");
        GlueCatalogStorage<String> store = new GlueCatalogStorage<>(backend, resolver);
        assertEquals(List.of("legacy"), store.scan(key -> key.startsWith("db:")));
        assertTrue(backend.get("db:table").isEmpty());
        store.put("db:table", "updated");
        backend.put("db:table", "stale");
        assertEquals(Set.of("db:table"), store.keys());
        assertEquals(List.of("updated"), store.scan(key -> true));
        assertTrue(backend.get("db:table").isEmpty());

        GlueCatalogStorage<String> newDefault = new GlueCatalogStorage<>(backend,
                new RegionResolver("ap-southeast-2", ACCOUNT));
        assertTrue(newDefault.get("db:table").isEmpty());
        assertTrue(newDefault.keys().isEmpty());
        GlueCatalogStorage<String> originalRegion = new GlueCatalogStorage<>(backend,
                new RegionResolver("ap-southeast-2", ACCOUNT) {
                    @Override
                    public String getRegion() {
                        return DEFAULT_REGION;
                    }
                });
        assertEquals("updated", originalRegion.get("db:table").orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void factoryRestartPreservesLegacyAndRegionalKeys(String mode, @TempDir Path directory) {
        StorageFactory factory = factory(mode, directory);
        try {
            AccountAwareStorageBackend<String> backend = factory.create("glue", "catalog.json", new TypeReference<>() {});
            backend.put("db:table", "legacy-default");
            GlueCatalogStorage<String> store = new GlueCatalogStorage<>(backend, resolver);
            context.setRegion(OTHER_REGION);
            assertTrue(store.get("db:table").isEmpty());
            store.put("db:table", "regional");
            backend.putForAccount(OTHER_ACCOUNT, "db:table", "foreign-default");
        } finally {
            factory.shutdownAll();
        }

        StorageFactory reopened = factory(mode, directory);
        try {
            AccountAwareStorageBackend<String> backend = reopened.create("glue", "catalog.json", new TypeReference<>() {});
            GlueCatalogStorage<String> store = new GlueCatalogStorage<>(backend, resolver);
            assertEquals(List.of("regional"), store.scan(key -> true));
            store.delete("db:table");
            context.setRegion(DEFAULT_REGION);
            assertEquals(Set.of("db:table"), store.keys());
            assertEquals("legacy-default", store.get("db:table").orElseThrow());
            assertEquals("foreign-default", backend.getForAccount(OTHER_ACCOUNT, "db:table").orElseThrow());
            store.put("db:table", "updated-default");
        } finally {
            reopened.shutdownAll();
        }

        StorageFactory finalRestart = factory(mode, directory);
        try {
            GlueCatalogStorage<String> store = new GlueCatalogStorage<>(
                    finalRestart.create("glue", "catalog.json", new TypeReference<>() {}), resolver);
            assertEquals("updated-default", store.get("db:table").orElseThrow());
            context.setRegion(OTHER_REGION);
            assertTrue(store.get("db:table").isEmpty());
            assertTrue(store.keys().isEmpty());
        } finally {
            finalRestart.shutdownAll();
        }
    }

    private AccountAwareStorageBackend<String> accounts(StorageBackend<String, String> raw) {
        @SuppressWarnings("unchecked")
        Instance<RequestContext> instance = mock(Instance.class);
        when(instance.get()).thenReturn(context);
        return new AccountAwareStorageBackend<>(raw, instance, ACCOUNT);
    }

    private static StorageFactory factory(String mode, Path directory) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.defaultAccountId()).thenReturn(ACCOUNT);
        when(config.storage().persistentPath()).thenReturn(directory.toString());
        when(config.storage().wal().compactionIntervalMs()).thenReturn(60_000L);
        ServiceConfigAccess services = mock(ServiceConfigAccess.class);
        when(services.storageMode("glue")).thenReturn(mode);
        when(services.storageFlushInterval("glue")).thenReturn(60_000L);
        return new StorageFactory(config, services);
    }
}
