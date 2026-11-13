package io.floci.aws.services.eventbridge;

/**
 * A Floci service that receives the events of rules targeting its own resources' ARNs, as AWS services do for the
 * managed rules they create (User Notifications' event rules, for one). EventBridge offers a target ARN no built-in
 * delivery handles to each of these beans, in turn. Nothing injects them, so they're marked
 * {@code @io.quarkus.arc.Unremovable}.
 */
public interface ServiceEventTarget {

    /** Whether this service receives events for a target ARN. */
    boolean handles(String targetArn);

    /** Receives a matched event, as the target's input shapes it. */
    void deliver(String targetArn, String event, String region);
}
