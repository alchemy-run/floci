package io.github.hectorvent.floci.services.translate;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies Translate terminologies survive a restart. Two service instances share the same
 * {@link StorageFactory} backend; the second simulates a process restart reloading from disk.
 */
class TranslateServicePersistenceTest {

    @Test
    void terminologiesSurviveRestart() {
        SharedStorageFactory storage = new SharedStorageFactory();

        TranslateService first = serviceWithStorage(storage);
        first.importTerminology(importBody("persist-glossary"), "us-east-1");

        TranslateService reloaded = serviceWithStorage(storage);
        ObjectNode got = reloaded.getTerminology(nameBody("persist-glossary"), "us-east-1");
        assertEquals("en", got.path("TerminologyProperties").path("SourceLanguageCode").asText());
        assertEquals(1, got.path("TerminologyProperties").path("TermCount").asInt());
    }

    @Test
    void deletedTerminologyDoesNotReappearAfterRestart() {
        SharedStorageFactory storage = new SharedStorageFactory();

        TranslateService first = serviceWithStorage(storage);
        first.importTerminology(importBody("keep"), "us-east-1");
        first.importTerminology(importBody("drop"), "us-east-1");
        first.deleteTerminology(nameBody("drop"));

        TranslateService reloaded = serviceWithStorage(storage);
        assertEquals("keep", reloaded.getTerminology(nameBody("keep"), "us-east-1")
                .path("TerminologyProperties").path("Name").asText());
        AwsException thrown = assertThrows(AwsException.class,
                () -> reloaded.getTerminology(nameBody("drop"), "us-east-1"));
        assertEquals("ResourceNotFoundException", thrown.getErrorCode());
    }

    private static TranslateService serviceWithStorage(StorageFactory storage) {
        TranslateService service = new TranslateService(
                storage, new ObjectMapper(), new RegionResolver("us-east-1", "123456789012"));
        service.initializeStorage();
        return service;
    }

    private static ObjectNode importBody(String name) {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode();
        root.put("Name", name);
        root.put("MergeStrategy", "OVERWRITE");
        ObjectNode data = root.putObject("TerminologyData");
        data.put("File", Base64.getEncoder().encodeToString("en,es\nAlchemy,Alquimia".getBytes(StandardCharsets.UTF_8)));
        data.put("Format", "CSV");
        return root;
    }

    private static ObjectNode nameBody(String name) {
        ObjectNode root = new ObjectMapper().createObjectNode();
        root.put("Name", name);
        return root;
    }

    private static final class SharedStorageFactory extends StorageFactory {
        private final Map<String, StorageBackend<String, ?>> stores = new HashMap<>();

        private SharedStorageFactory() {
            super(null, null);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <V> StorageBackend<String, V> create(String serviceName,
                                                    String fileName,
                                                    TypeReference<Map<String, V>> typeReference) {
            return (StorageBackend<String, V>) stores.computeIfAbsent(fileName, ignored -> new InMemoryStorage<>());
        }
    }
}
