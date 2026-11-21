package io.floci.aws.core.common;

import io.floci.aws.config.EmulatorConfig;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Finishes a delete AWS runs in the background. The resource reads back in its deleting state until
 * {@code services.async-delete-millis} has passed, then {@code removal} runs in the account and Region
 * of the request that deleted it. A second delete in that window meets a delete already under way, as
 * it does on AWS. At 0 the removal runs at once, inside the request.
 */
@ApplicationScoped
public class DeferredRemovals {

    private static final Logger LOG = Logger.getLogger(DeferredRemovals.class);

    private final EmulatorConfig config;
    private final RegionResolver regionResolver;
    private final Set<String> pending = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "deferred-removals");
        thread.setDaemon(true);
        return thread;
    });

    @Inject
    public DeferredRemovals(EmulatorConfig config, RegionResolver regionResolver) {
        this.config = config;
        this.regionResolver = regionResolver;
    }

    /**
     * Runs {@code removal} once the deleting window has passed, or now when there is none. Until it has
     * run, {@link #isPending} answers true for {@code key}, which names the resource within its service.
     * A removal that fails leaves the resource as the service left it, so a later delete can retry it.
     */
    public void afterDeleting(String key, Runnable removal) {
        long millis = config.services().asyncDeleteMillis();
        if (millis <= 0) {
            removal.run();
            return;
        }
        String accountId = regionResolver.getAccountId();
        String region = regionResolver.getRegion();
        String scoped = scoped(accountId, key);
        pending.add(scoped);
        scheduler.schedule(() -> {
            try {
                RequestScopes.runAs(accountId, region, removal);
            } catch (RuntimeException e) {
                LOG.warnv("Could not finish deleting {0}: {1}", key, e.getMessage());
            } finally {
                pending.remove(scoped);
            }
        }, millis, TimeUnit.MILLISECONDS);
    }

    /** Whether a delete of {@code key} in the caller's account is under way and not yet finished. */
    public boolean isPending(String key) {
        return pending.contains(scoped(regionResolver.getAccountId(), key));
    }

    private static String scoped(String accountId, String key) {
        return accountId + "/" + key;
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }
}
