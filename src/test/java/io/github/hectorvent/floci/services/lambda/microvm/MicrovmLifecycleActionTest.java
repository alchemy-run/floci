package io.github.hectorvent.floci.services.lambda.microvm;

import io.github.hectorvent.floci.services.lambda.microvm.model.MicrovmRecord;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.github.hectorvent.floci.services.lambda.microvm.MicrovmRuntimeService.nextLifecycleAction;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The idle-policy / max-duration decision table behind the reaper sweep. */
class MicrovmLifecycleActionTest {

    private static final long NOW = 1_000_000_000L;

    private MicrovmRecord vm(String state) {
        MicrovmRecord vm = new MicrovmRecord();
        vm.setState(state);
        vm.setStartedAt(NOW - 60_000);
        vm.setLastActivityAt(NOW - 60_000);
        vm.setMaximumDurationInSeconds(3600);
        vm.setIdlePolicy(Map.of(
                "maxIdleDurationSeconds", 300,
                "suspendedDurationSeconds", 900,
                "autoResumeEnabled", Boolean.TRUE));
        return vm;
    }

    @Test
    void runningWithRecentActivityIsLeftAlone() {
        assertNull(nextLifecycleAction(vm("RUNNING"), NOW));
    }

    @Test
    void runningIdleBeyondMaxIdleSuspends() {
        MicrovmRecord vm = vm("RUNNING");
        vm.setLastActivityAt(NOW - 301_000);
        vm.setStartedAt(NOW - 400_000);
        assertEquals("suspend", nextLifecycleAction(vm, NOW));
    }

    @Test
    void idleClockStartsAtLaunchWhenNoActivityRecorded() {
        MicrovmRecord vm = vm("RUNNING");
        vm.setLastActivityAt(0);
        vm.setStartedAt(NOW - 301_000);
        assertEquals("suspend", nextLifecycleAction(vm, NOW));
    }

    @Test
    void suspendedWithinWindowIsLeftAlone() {
        MicrovmRecord vm = vm("SUSPENDED");
        vm.setSuspendedAt(NOW - 60_000);
        assertNull(nextLifecycleAction(vm, NOW));
    }

    @Test
    void suspendedBeyondWindowExpires() {
        MicrovmRecord vm = vm("SUSPENDED");
        vm.setSuspendedAt(NOW - 901_000);
        assertEquals("expire", nextLifecycleAction(vm, NOW));
    }

    @Test
    void maxDurationTerminatesRegardlessOfActivity() {
        MicrovmRecord vm = vm("RUNNING");
        vm.setStartedAt(NOW - 3_601_000);
        vm.setLastActivityAt(NOW);
        assertEquals("max-duration", nextLifecycleAction(vm, NOW));
    }

    @Test
    void terminatedIsNeverActedOn() {
        MicrovmRecord vm = vm("TERMINATED");
        vm.setStartedAt(NOW - 10_000_000);
        assertNull(nextLifecycleAction(vm, NOW));
    }

    @Test
    void withoutIdlePolicyOnlyMaxDurationApplies() {
        MicrovmRecord vm = vm("RUNNING");
        vm.setIdlePolicy(null);
        vm.setLastActivityAt(NOW - 10_000_000);
        vm.setStartedAt(NOW - 60_000);
        assertNull(nextLifecycleAction(vm, NOW));
    }
}
