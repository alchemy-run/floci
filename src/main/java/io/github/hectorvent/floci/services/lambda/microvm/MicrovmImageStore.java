package io.github.hectorvent.floci.services.lambda.microvm;

import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.lambda.microvm.model.MicrovmImageRecord;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class MicrovmImageStore {

    private final StorageBackend<String, MicrovmImageRecord> backend;

    @Inject
    public MicrovmImageStore(StorageFactory storageFactory) {
        this.backend = storageFactory.create("lambda", "lambda-microvm-images.json",
                new TypeReference<Map<String, MicrovmImageRecord>>() {});
    }

    MicrovmImageStore(StorageBackend<String, MicrovmImageRecord> backend) {
        this.backend = backend;
    }

    public void save(MicrovmImageRecord image) {
        backend.put(key(image.getRegion(), image.getName()), image);
    }

    public Optional<MicrovmImageRecord> get(String region, String name) {
        return backend.get(key(region, name));
    }

    /** Resolve by name OR by full image ARN (the API accepts either). */
    public Optional<MicrovmImageRecord> resolve(String region, String nameOrArn) {
        if (nameOrArn == null) {
            return Optional.empty();
        }
        String name = nameOrArn;
        if (nameOrArn.startsWith("arn:")) {
            int idx = nameOrArn.indexOf(":microvm-image/");
            if (idx < 0) {
                return Optional.empty();
            }
            name = nameOrArn.substring(idx + ":microvm-image/".length());
        }
        return get(region, name);
    }

    public List<MicrovmImageRecord> list(String region) {
        String prefix = "microvm-image::" + region + "::";
        return backend.scan(k -> k.startsWith(prefix));
    }

    public void delete(String region, String name) {
        backend.delete(key(region, name));
    }

    private static String key(String region, String name) {
        return "microvm-image::" + region + "::" + name;
    }
}
