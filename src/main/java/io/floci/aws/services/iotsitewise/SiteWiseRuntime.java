package io.floci.aws.services.iotsitewise;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Restarts what runs in the background once Floci restarts with persisted SiteWise state: metric windows for its
 * assets, scheduled anomaly inference, and cold tier replication.
 */
@ApplicationScoped
public class SiteWiseRuntime {

    private final SiteWiseStore store;
    private final SiteWiseCompute compute;
    private final SiteWiseComputations computations;
    private final SiteWiseSettings settings;

    @Inject
    public SiteWiseRuntime(SiteWiseStore store, SiteWiseCompute compute, SiteWiseComputations computations,
                           SiteWiseSettings settings) {
        this.store = store;
        this.compute = compute;
        this.computations = computations;
        this.settings = settings;
    }

    public void restorePersistedRuntime() {
        if (!store.everywhere(key -> key.contains("/" + SiteWiseStore.ASSET + "/")).isEmpty()) {
            compute.start();
            computations.startScheduler();
        }
        if (store.everywhere(key -> key.endsWith("/" + SiteWiseStore.SETTINGS + "/account")).stream()
                .anyMatch(entry -> entry.value().path("storage").has("multiLayerStorage"))) {
            settings.startExporter();
        }
    }
}
