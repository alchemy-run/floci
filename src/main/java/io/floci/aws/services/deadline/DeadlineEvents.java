package io.floci.aws.services.deadline;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.services.eventbridge.EventBridgeService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/**
 * The events Deadline Cloud sends to the default event bus with source {@code aws.deadline}: lifecycle and run
 * status changes of jobs, steps and tasks, and budget thresholds. Each detail names the farm and the affected
 * resources and carries the status before and after.
 */
@ApplicationScoped
public class DeadlineEvents {

    static final String SOURCE = "aws.deadline";

    private final Instance<EventBridgeService> events;

    @Inject
    public DeadlineEvents(Instance<EventBridgeService> events) {
        this.events = events;
    }

    void publish(String region, String detailType, ObjectNode detail, String resourceArn) {
        if (events.isResolvable()) {
            events.get().publishServiceEvent(SOURCE, detailType, detail.toString(), resourceArn, region);
        }
    }
}
