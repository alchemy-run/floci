package io.github.hectorvent.floci.services.lambda.durable;

import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.lambda.durable.model.DurableExecution;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

/**
 * Wraps the storage backend for Lambda Durable Executions, keyed by execution ARN.
 */
@ApplicationScoped
public class DurableExecutionStore {

    private final StorageBackend<String, DurableExecution> backend;

    @Inject
    public DurableExecutionStore(StorageFactory storageFactory) {
        this.backend = storageFactory.create("lambda", "lambda-durable-executions.json",
                new TypeReference<>() {
                });
    }

    DurableExecutionStore(StorageBackend<String, DurableExecution> backend) {
        this.backend = backend;
    }

    public void save(DurableExecution execution) {
        backend.put(execution.getExecutionArn(), execution);
    }

    public Optional<DurableExecution> get(String executionArn) {
        return backend.get(executionArn);
    }

    public List<DurableExecution> list() {
        return backend.scan(k -> true);
    }

    /**
     * Every execution across every account. Startup timer re-arm and chained-invoke
     * completion run outside a request, so they must not be scoped to the default
     * account prefix.
     */
    public List<DurableExecution> listAllAccounts() {
        if (backend instanceof AccountAwareStorageBackend<DurableExecution> aware) {
            return aware.scanAllAccounts();
        }
        return backend.scan(k -> true);
    }

    /** All executions of one function (short name) in a region, across qualifiers. */
    public List<DurableExecution> listByFunction(String region, String functionName) {
        List<DurableExecution> all = backend.scan(k -> true);
        all.removeIf(e -> !region.equals(e.getRegion()) || !functionName.equals(e.getFunctionName()));
        return all;
    }

    /** The execution registered under a name for a function, if any (names are unique per function). */
    public Optional<DurableExecution> getByName(String region, String functionName, String executionName) {
        return listByFunction(region, functionName).stream()
                .filter(e -> executionName.equals(e.getExecutionName()))
                .findFirst();
    }

    public void delete(String executionArn) {
        backend.delete(executionArn);
    }
}
