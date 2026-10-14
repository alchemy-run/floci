package io.github.hectorvent.floci.services.apigateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.config.TlsCertificateManager;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.ServiceConfigAccess;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.apigateway.model.BasePathMapping;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Mapping IDs identify one persisted record across renames and restarts.
 * Legacy records derive their ID from the exact stored path, not its canonical spelling.
 */
class ApiMappingIdTest {

    @Test
    void rootLikeKeysThatWerePersistedSeparatelyKeepSeparateIds() {
        String canonical = ApiGatewayController.apiMappingId("(none)");
        String slash = ApiGatewayController.apiMappingId("/");
        String empty = ApiGatewayController.apiMappingId("");

        assertNotEquals(canonical, slash);
        assertNotEquals(canonical, empty);
        assertNotEquals(slash, empty);
    }

    @Test
    void everyIdIsNonEmptyAndStable() {
        // A record stored under an empty key must still produce an id a caller can put in a URL.
        assertFalse(ApiGatewayController.apiMappingId("").isEmpty());
        assertFalse(ApiGatewayController.apiMappingId(null).isEmpty());

        assertEquals(ApiGatewayController.apiMappingId("orders"),
                ApiGatewayController.apiMappingId("orders"));
    }

    @Test
    void distinctKeysSharingAJavaHashKeepDistinctIds() {
        assertEquals("Aa".hashCode(), "BB".hashCode());
        assertNotEquals(ApiGatewayController.apiMappingId("Aa"),
                ApiGatewayController.apiMappingId("BB"));
    }

    @Test
    void persistedNewIdsSurviveRenameRestartAndKeyReuse(@TempDir Path directory) {
        String region = "us-east-1";
        String domain = "persisted-mapping.example.test";
        ApiGatewayService service = newService(directory);
        service.createDomainName(region, Map.of("domainName", domain));
        BasePathMapping created = service.createBasePathMapping(region, domain,
                Map.of("restApiId", "first", "stage", "blue", "basePath", "old"));
        String id = created.getApiMappingId();
        assertNotNull(id);
        assertNotEquals(ApiGatewayController.apiMappingId("old"), id);

        service = newService(directory);
        assertEquals(id, service.getApiMapping(region, domain, id).mapping().getApiMappingId());
        service.updateApiMapping(region, domain, id,
                Map.of("apiId", "second", "stage", "green", "apiMappingKey", "new"),
                (apiId, stage) -> {
                    assertEquals("second", apiId);
                    assertEquals("green", stage);
                });
        service = newService(directory);
        ApiGatewayService.StoredMapping updated = service.getApiMapping(region, domain, id);
        assertEquals("new", updated.storedPath());
        assertEquals("second", updated.mapping().getRestApiId());
        assertEquals("green", updated.mapping().getStage());
        assertEquals(id, updated.mapping().getApiMappingId());
        assertFalse(service.basePathMappingsByStoredPath(region, domain).containsKey("old"));

        String reusedId = service.createBasePathMapping(region, domain,
                Map.of("restApiId", "first", "stage", "blue", "basePath", "old")).getApiMappingId();
        assertNotEquals(id, reusedId);
        service.deleteApiMapping(region, domain, id);
        ApiGatewayService restored = newService(directory);
        assertThrows(AwsException.class, () -> restored.getApiMapping(region, domain, id));
        assertEquals("old", restored.getApiMapping(region, domain, reusedId).storedPath());
    }

    @Test
    void persistedLegacyRootsRemainDistinctAndFreezeTheirOriginalIdsOnUpdate(@TempDir Path directory)
            throws Exception {
        String region = "us-east-1";
        String domain = "persisted-legacy-mapping.example.test";
        newService(directory).createDomainName(region, Map.of("domainName", domain));
        Map<String, Object> records = new LinkedHashMap<>();
        List<String> paths = List.of("", "/", " ", "(none)");
        for (String path : paths) {
            records.put("000000000000/" + region + "::" + domain + "::" + path,
                    Map.of("basePath", "(none)", "restApiId", "api-" + path, "stage", "blue"));
        }
        Files.writeString(directory.resolve("apigateway-mappings.json"),
                new ObjectMapper().writeValueAsString(records));
        ApiGatewayService service = newService(directory);
        for (String path : paths) {
            ApiGatewayService.StoredMapping found = service.getApiMapping(region, domain,
                    ApiGatewayController.apiMappingId(path));
            assertEquals(path, found.storedPath());
            assertEquals("api-" + path, found.mapping().getRestApiId());
            assertNull(found.mapping().getApiMappingId());
        }

        String canonicalId = ApiGatewayController.apiMappingId("(none)");
        service.updateBasePathMapping(region, domain, "(none)",
                List.of(Map.of("op", "replace", "path", "/stage", "value", "green")));
        assertEquals(canonicalId, newService(directory).getApiMapping(region, domain, canonicalId)
                .mapping().getApiMappingId());

        String legacyId = ApiGatewayController.apiMappingId("");
        assertThrows(AwsException.class, () -> service.updateApiMapping(region, domain, legacyId,
                Map.of("apiId", "invalid", "apiMappingKey", "renamed"), (apiId, stage) -> {
                    throw new AwsException("BadRequestException", "Invalid API identifier specified", 400);
                }));
        assertNull(newService(directory).getApiMapping(region, domain, legacyId).mapping().getApiMappingId());
        assertFalse(service.basePathMappingsByStoredPath(region, domain).containsKey("renamed"));
        assertThrows(AwsException.class, () -> service.updateApiMapping(region, domain, legacyId,
                Map.of("apiId", "target", "apiMappingKey", "/"), (apiId, stage) -> {
                    throw new AssertionError("A conflicting rename must not validate or mutate its target");
                }));
        assertNull(service.getApiMapping(region, domain, legacyId).mapping().getApiMappingId());

        service.updateApiMapping(region, domain, legacyId, Map.of("apiId", "target"), (apiId, stage) -> {
            assertEquals("target", apiId);
            assertEquals("blue", stage);
        });
        ApiGatewayService afterTargetUpdate = newService(directory);
        assertEquals("", afterTargetUpdate.getApiMapping(region, domain, legacyId).storedPath());
        assertEquals(legacyId, afterTargetUpdate.getApiMapping(region, domain, legacyId).mapping().getApiMappingId());
        afterTargetUpdate.updateApiMapping(region, domain, legacyId,
                Map.of("apiId", "target", "apiMappingKey", "renamed"), (apiId, stage) -> {
                    assertEquals("target", apiId);
                    assertEquals("blue", stage);
                });
        ApiGatewayService restored = newService(directory);
        assertEquals("renamed", restored.getApiMapping(region, domain, legacyId).storedPath());
        assertEquals(legacyId, restored.getApiMapping(region, domain, legacyId).mapping().getApiMappingId());
        assertFalse(restored.basePathMappingsByStoredPath(region, domain).containsKey(""));
        for (String path : List.of("/", " ", "(none)")) {
            String id = ApiGatewayController.apiMappingId(path);
            assertEquals(path, restored.getApiMapping(region, domain, id).storedPath());
            restored.deleteApiMapping(region, domain, id);
            assertThrows(AwsException.class, () -> restored.getApiMapping(region, domain, id));
            assertEquals("renamed", restored.getApiMapping(region, domain, legacyId).storedPath());
        }
        String rootId = restored.createBasePathMapping(region, domain,
                Map.of("restApiId", "fresh-root", "stage", "blue")).getApiMappingId();
        assertNotEquals(legacyId, rootId);
        restored.deleteApiMapping(region, domain, legacyId);
        ApiGatewayService finalReload = newService(directory);
        assertThrows(AwsException.class, () -> finalReload.getApiMapping(region, domain, legacyId));
        assertEquals(rootId, finalReload.getApiMapping(region, domain, rootId).mapping().getApiMappingId());
    }

    private static ApiGatewayService newService(Path directory) {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.StorageConfig storage = mock(EmulatorConfig.StorageConfig.class);
        when(config.storage()).thenReturn(storage);
        when(storage.persistentPath()).thenReturn(directory.toString());
        when(config.defaultAccountId()).thenReturn("000000000000");
        ServiceConfigAccess access = mock(ServiceConfigAccess.class);
        when(access.storageMode("apigateway")).thenReturn("persistent");
        StorageFactory factory = new StorageFactory(config, access);
        return new ApiGatewayService(factory, config, mock(TlsCertificateManager.class));
    }
}
