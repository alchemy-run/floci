package io.github.hectorvent.floci.services.account.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * An account's opt-in status for one opt-in region. A transitional status ({@code ENABLING} or
 * {@code DISABLING}) is reported for {@code pendingReads} more observations before it settles.
 */
@RegisterForReflection
public record RegionOptState(String status, int pendingReads) {
}
