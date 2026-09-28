package io.github.hectorvent.floci.services.sagemaker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.s3.S3Service;
import jakarta.enterprise.inject.Instance;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Plumbing shared by the SageMaker Feature Store and HyperPod control planes: account-explicit
 * storage access (asynchronous status transitions run outside any request context), delayed
 * transitions, EventBridge state-change events, and S3/IAM existence checks.
 */
final class SageMakerStateSupport {
    private static final Logger LOG = Logger.getLogger(SageMakerStateSupport.class);

    private SageMakerStateSupport() {
    }

    /** Publishes one {@code aws.sagemaker} event. */
    @FunctionalInterface
    interface StateEvents {
        void publish(String region, String accountId, String detailType, String resourceArn, ObjectNode detail);

        StateEvents NONE = (region, accountId, detailType, resourceArn, detail) -> { };
    }

    /** Runs a task once after a delay. */
    @FunctionalInterface
    interface Scheduler {
        void schedule(Duration delay, Runnable task);

        Scheduler NONE = (delay, task) -> { };

        Scheduler DELAYED = (delay, task) -> CompletableFuture.runAsync(task,
                CompletableFuture.delayedExecutor(delay.toMillis(), TimeUnit.MILLISECONDS));
    }

    static StateEvents eventBridge(Instance<EventBridgeService> eventBridge, ObjectMapper mapper) {
        return (region, accountId, detailType, resourceArn, detail) -> {
            if (eventBridge == null || !eventBridge.isResolvable()) {
                return;
            }
            try {
                ArrayNode resources = mapper.createArrayNode();
                resources.add(resourceArn);
                Map<String, Object> entry = new HashMap<>();
                entry.put("Source", "aws.sagemaker");
                entry.put("DetailType", detailType);
                entry.put("Detail", mapper.writeValueAsString(detail));
                entry.put("Resources", resources);
                eventBridge.get().putEvents(List.of(entry), region, accountId);
            } catch (Exception e) {
                LOG.warnv("Failed to publish SageMaker {0} for {1}: {2}", detailType, resourceArn, e.getMessage());
            }
        };
    }

    static Predicate<String> bucketExists(Instance<S3Service> s3) {
        return bucket -> s3 != null && s3.isResolvable() && s3.get().bucketExists(bucket);
    }

    /** Whether {@code roleArn} names an IAM role that exists in its account (or the caller's). */
    static Predicate<String> roleExists(Instance<IamService> iam, Supplier<String> callerAccount) {
        return roleArn -> {
            if (iam == null || !iam.isResolvable()) {
                return false;
            }
            String[] parts = roleArn.split(":", 6);
            if (parts.length < 6 || !parts[5].startsWith("role/")) {
                return false;
            }
            String name = parts[5].substring(parts[5].lastIndexOf('/') + 1);
            IamService service = iam.get();
            return service.findRole(parts[4], name).isPresent()
                    || service.findRole(callerAccount.get(), name).isPresent();
        };
    }

    /** Whether {@code arn} is shaped like an IAM role ARN. */
    static boolean isRoleArn(String arn) {
        String[] parts = arn.split(":", 6);
        return parts.length == 6 && "arn".equals(parts[0]) && "iam".equals(parts[2])
                && parts[5].startsWith("role/") && parts[5].length() > "role/".length();
    }

    static <V> Optional<V> getFor(StorageBackend<String, V> store, String accountId, String key) {
        if (accountId != null && store instanceof AccountAwareStorageBackend<V> aware) {
            return aware.getForAccount(accountId, key);
        }
        return store.get(key);
    }

    static <V> void putFor(StorageBackend<String, V> store, String accountId, String key, V value) {
        if (accountId != null && store instanceof AccountAwareStorageBackend<V> aware) {
            aware.putForAccount(accountId, key, value);
        } else {
            store.put(key, value);
        }
    }

    static <V> void deleteFor(StorageBackend<String, V> store, String accountId, String key) {
        if (accountId != null && store instanceof AccountAwareStorageBackend<V> aware) {
            aware.deleteForAccount(accountId, key);
        } else {
            store.delete(key);
        }
    }

    static <V> List<String> keysFor(StorageBackend<String, V> store, String accountId, Predicate<String> filter) {
        if (accountId != null && store instanceof AccountAwareStorageBackend<V> aware) {
            return aware.keysForAccount(accountId).stream().filter(filter).toList();
        }
        return store.keys().stream().filter(filter).toList();
    }
}
