package io.floci.aws.services.redshiftserverless;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.SweepScheduler;
import io.floci.aws.core.common.SweepWorker;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** Takes and expires Serverless recovery points outside AWS request threads, one sweep a minute. */
@ApplicationScoped
public class RedshiftServerlessRecoveryPointScheduler extends SweepScheduler {
    @Inject
    public RedshiftServerlessRecoveryPointScheduler(RedshiftServerlessRecoveryService recovery,
                                                    EmulatorConfig config) {
        super(worker(recovery, config.services().redshiftServerless()));
    }

    private static SweepWorker worker(RedshiftServerlessRecoveryService recovery,
                                      EmulatorConfig.RedshiftServerlessServiceConfig serverless) {
        return new SweepWorker("redshift-serverless-recovery-points",
                serverless.enabled() && serverless.recoveryPointsEnabled(),
                Math.min(60, serverless.recoveryPointIntervalSeconds()), true, recovery::sweep);
    }
}
