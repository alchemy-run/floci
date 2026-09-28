package io.github.hectorvent.floci.services.lambda.microvm;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.lambda.microvm.model.MicrovmImageRecord;
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
        if (backend instanceof AccountAwareStorageBackend<MicrovmImageRecord> aware) {
            aware.putForAccount(image.getAccountId(), key(image.getRegion(), image.getName()), image);
        } else {
            backend.put(key(image.getRegion(), image.getName()), image);
        }
    }

    public Optional<MicrovmImageRecord> get(String region, String name) {
        return backend.get(key(region, name));
    }

    public Optional<MicrovmImageRecord> getForAccount(String accountId, String region, String name) {
        if (backend instanceof AccountAwareStorageBackend<MicrovmImageRecord> aware) {
            return aware.getForAccount(accountId, key(region, name));
        }
        return get(region, name);
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
                idx = nameOrArn.indexOf(":microvm-image:");
            }
            if (idx < 0) {
                return Optional.empty();
            }
            name = nameOrArn.substring(idx + ":microvm-image/".length());
        }
        String requestedArn = nameOrArn.replace(":microvm-image/", ":microvm-image:");
        return get(region, name).filter(image -> !nameOrArn.startsWith("arn:")
                || requestedArn.equals(image.getImageArn().replace(":microvm-image/", ":microvm-image:")));
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
