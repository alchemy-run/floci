package io.github.hectorvent.floci.services.lambda.microvm;

import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.lambda.microvm.model.MicrovmRecord;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class MicrovmStore {

    private final StorageBackend<String, MicrovmRecord> backend;

    @Inject
    public MicrovmStore(StorageFactory storageFactory) {
        this.backend = storageFactory.create("lambda", "lambda-microvms.json",
                new TypeReference<Map<String, MicrovmRecord>>() {});
    }

    MicrovmStore(StorageBackend<String, MicrovmRecord> backend) {
        this.backend = backend;
    }

    public void save(MicrovmRecord microvm) {
        backend.put(key(microvm.getRegion(), microvm.getMicrovmId()), microvm);
    }

    public Optional<MicrovmRecord> get(String region, String microvmId) {
        return backend.get(key(region, microvmId));
    }

    /** Lookup across regions by id alone (used by the endpoint proxy, which only has the hostname). */
    public Optional<MicrovmRecord> findById(String microvmId) {
        return backend.scan(k -> k.endsWith("::" + microvmId)).stream().findFirst();
    }

    public List<MicrovmRecord> list(String region) {
        String prefix = "microvm::" + region + "::";
        return backend.scan(k -> k.startsWith(prefix));
    }

    public List<MicrovmRecord> listAll() {
        return backend.scan(k -> true);
    }

    public void delete(String region, String microvmId) {
        backend.delete(key(region, microvmId));
    }

    private static String key(String region, String microvmId) {
        return "microvm::" + region + "::" + microvmId;
    }
}
