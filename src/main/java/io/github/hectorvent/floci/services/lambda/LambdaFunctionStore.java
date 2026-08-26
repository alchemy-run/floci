package io.github.hectorvent.floci.services.lambda;

import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Wraps the storage backend for Lambda functions with region-aware key logic.
 */
@ApplicationScoped
public class LambdaFunctionStore implements Resettable {

    /**
     * Survives {@code @ApplicationScoped} reconstruction during quarkus:dev
     * live-reload of <em>other</em> services. Memory-backed stores are new and
     * empty after a reload, so Function URL invocations 404 unless the
     * previous generation's functions are copied back in. Cleared on
     * {@link #clear()} so {@code /_floci/state/reset} still empties the
     * emulator.
     */
    private static final ConcurrentHashMap<String, LambdaFunction> PROCESS_URL_INDEX =
            new ConcurrentHashMap<>();

    private final StorageBackend<String, LambdaFunction> backend;
    private final ConcurrentHashMap<String, LambdaFunction> urlIdIndex = new ConcurrentHashMap<>();

    @Inject
    public LambdaFunctionStore(StorageFactory storageFactory) {
        this.backend = storageFactory.create("lambda", "lambda-functions.json",
                new TypeReference<>() {
                });
        loadIndex();
    }

    LambdaFunctionStore(StorageBackend<String, LambdaFunction> backend) {
        this.backend = backend;
        loadIndex();
    }

    private void loadIndex() {
        List<LambdaFunction> all = backend instanceof AccountAwareStorageBackend<LambdaFunction> aware
                ? aware.scanAllAccounts()
                : backend.scan(key -> true);
        all.forEach(this::indexFunction);
        PROCESS_URL_INDEX.forEach((id, fn) -> restoreIntoBackend(fn));
    }

    public void clear() {
        urlIdIndex.clear();
        PROCESS_URL_INDEX.clear();
    }

    private void indexFunction(LambdaFunction fn) {
        if (!hasFunctionUrl(fn)) {
            return;
        }
        String urlId = extractUrlId(fn.getUrlConfig().getFunctionUrl());
        if (urlId != null) {
            urlIdIndex.put(urlId, fn);
            PROCESS_URL_INDEX.put(urlId, fn);
        }
    }

    private void deindexFunction(LambdaFunction fn) {
        if (!hasFunctionUrl(fn)) {
            return;
        }
        String urlId = extractUrlId(fn.getUrlConfig().getFunctionUrl());
        if (urlId != null) {
            urlIdIndex.remove(urlId);
            PROCESS_URL_INDEX.remove(urlId);
        }
    }

    private String extractUrlId(String url) {
        // http://urlId.lambda-url.region.baseHost/
        int start = url.indexOf("://");
        if (start < 0) return null;
        int end = url.indexOf(".", start + 3);
        if (end < 0) return null;
        return url.substring(start + 3, end);
    }

    public void save(String region, LambdaFunction fn) {
        Optional<LambdaFunction> existing = get(region, fn.getFunctionName(), fn.getVersion());
        existing.ifPresent(old -> {
            // UpdateFunctionCode / config saves often round-trip a copy that
            // omitted urlConfig (or left an empty object without FunctionUrl).
            // Dropping it deindexes the Function URL and invocations 404.
            if (!hasFunctionUrl(fn) && hasFunctionUrl(old)) {
                fn.setUrlConfig(old.getUrlConfig());
            }
            deindexFunction(old);
        });
        backend.put(regionKey(region, fn.getFunctionName(), fn.getVersion()), fn);
        indexFunction(fn);
    }

    public Optional<LambdaFunction> get(String region, String functionName) {
        return get(region, functionName, "$LATEST");
    }

    public Optional<LambdaFunction> get(String region, String functionName, String version) {
        return backend.get(regionKey(region, functionName, version));
    }

    public Optional<LambdaFunction> getForAccount(String accountId, String region, String functionName) {
        if (backend instanceof AccountAwareStorageBackend<LambdaFunction> aware) {
            return aware.getForAccount(accountId, regionKey(region, functionName, "$LATEST"));
        }
        return backend.get(regionKey(region, functionName, "$LATEST"));
    }

    public Optional<LambdaFunction> getByUrlId(String urlId) {
        if (urlId == null || urlId.isBlank()) {
            return Optional.empty();
        }
        LambdaFunction cached = urlIdIndex.get(urlId);
        if (cached != null) {
            return Optional.of(cached);
        }
        cached = PROCESS_URL_INDEX.get(urlId);
        if (cached != null) {
            restoreIntoBackend(cached);
            return Optional.of(cached);
        }
        // Resettable.clear() only drops this map; quarkus:dev live-reload can
        // reconstruct the bean against a still-populated backend. Rebuild.
        loadIndex();
        return Optional.ofNullable(urlIdIndex.get(urlId));
    }

    public List<LambdaFunction> list(String region) {
        String prefix = "lambda::" + region + "::";
        return backend.scan(key -> key.startsWith(prefix) && key.endsWith("::$LATEST"));
    }

    public List<LambdaFunction> listVersions(String region, String functionName) {
        String prefix = "lambda::" + region + "::" + functionName + "::";
        return backend.scan(key -> key.startsWith(prefix));
    }

    public List<LambdaFunction> listAll() {
        return backend.scan(key -> true);
    }

    public void delete(String region, String functionName) {
        // Delete all versions
        listVersions(region, functionName).forEach(fn -> {
            deindexFunction(fn);
            backend.delete(regionKey(region, functionName, fn.getVersion()));
        });
    }

    /** Deletes ONE published version snapshot, leaving $LATEST and siblings. */
    public void deleteVersion(String region, String functionName, String version) {
        get(region, functionName, version).ifPresent(this::deindexFunction);
        backend.delete(regionKey(region, functionName, version));
    }

    private static String regionKey(String region, String functionName, String version) {
        return "lambda::" + region + "::" + functionName + "::" + (version != null ? version : "$LATEST");
    }

    private static boolean hasFunctionUrl(LambdaFunction fn) {
        return fn != null
                && fn.getUrlConfig() != null
                && fn.getUrlConfig().getFunctionUrl() != null
                && !fn.getUrlConfig().getFunctionUrl().isBlank();
    }

    private void restoreIntoBackend(LambdaFunction fn) {
        if (fn == null || fn.getFunctionName() == null) {
            return;
        }
        String region = regionOf(fn);
        String version = fn.getVersion() != null ? fn.getVersion() : "$LATEST";
        backend.put(regionKey(region, fn.getFunctionName(), version), fn);
        indexFunction(fn);
    }

    private static String regionOf(LambdaFunction fn) {
        String arn = fn.getFunctionArn();
        if (arn != null) {
            String[] parts = arn.split(":");
            if (parts.length > 3 && !parts[3].isBlank()) {
                return parts[3];
            }
        }
        return "us-east-1";
    }
}
