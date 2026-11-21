package io.floci.aws.services.eventbridge;

/**
 * Sees each event a bus accepts, such as a schema discoverer that infers schemas from a bus's traffic.
 * Discovered through CDI, so EventBridge doesn't import the service that observes. Called in the putting caller's
 * request context with the event as rules see it; it must return quickly and never fail the put.
 */
public interface EventBusObserver {

    /** An event {@code busName} in {@code region} of {@code accountId} accepted, as its JSON envelope. */
    void accepted(String accountId, String region, String busName, String eventJson);
}
