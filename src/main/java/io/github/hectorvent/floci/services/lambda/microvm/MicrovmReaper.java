package io.github.hectorvent.floci.services.lambda.microvm;

import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Drives {@link MicrovmRuntimeService#sweepIdlePolicies()}: without it the
 * {@code idlePolicy} and {@code maximumDurationInSeconds} accepted by
 * RunMicrovm are stored but never enforced, and every abandoned MicroVM's
 * Docker container runs until an explicit TerminateMicrovm — dev loops
 * accumulate containers indefinitely.
 */
@ApplicationScoped
public class MicrovmReaper {

    private final MicrovmRuntimeService runtimeService;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            r -> new Thread(r, "microvm-reaper"));

    @Inject
    public MicrovmReaper(MicrovmRuntimeService runtimeService) {
        this.runtimeService = runtimeService;
    }

    void start(@Observes StartupEvent event) {
        scheduler.scheduleWithFixedDelay(runtimeService::sweepIdlePolicies, 15, 15, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stop() {
        scheduler.shutdownNow();
    }
}
