package io.floci.aws.services.kendra;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.SweepScheduler;
import io.floci.aws.core.common.SweepWorker;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** Starts scheduled Kendra data source syncs, and resumes syncs a restart interrupted, outside request threads. */
@ApplicationScoped
public class KendraSyncScheduler extends SweepScheduler {
    @Inject
    public KendraSyncScheduler(KendraSources sources, EmulatorConfig config) {
        super(new SweepWorker("kendra-sync-scheduler", config.services().kendra().enabled(),
                config.services().kendra().syncSweepIntervalSeconds(), true, sources::sweep));
    }
}
