package io.github.hectorvent.floci.services.dms;

import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

/** Runs every task on the submitting thread, so a probe's outcome is recorded before the call returns. */
final class InlineExecutorService extends AbstractExecutorService {

    private volatile boolean shutdown;

    @Override
    public void execute(Runnable command) {
        command.run();
    }

    @Override
    public void shutdown() {
        shutdown = true;
    }

    @Override
    public List<Runnable> shutdownNow() {
        shutdown = true;
        return List.of();
    }

    @Override
    public boolean isShutdown() {
        return shutdown;
    }

    @Override
    public boolean isTerminated() {
        return shutdown;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
        return true;
    }
}
