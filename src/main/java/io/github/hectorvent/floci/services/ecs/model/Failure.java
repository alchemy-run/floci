package io.github.hectorvent.floci.services.ecs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * A per-resource failure returned alongside the results of an ECS {@code Describe*} call.
 * ECS reports a resource it could not find here rather than throwing, so callers get partial
 * results; {@code reason} is {@code MISSING} for a resource that does not exist.
 */
@RegisterForReflection
public record Failure(String arn, String reason, String detail) {

    public static Failure missing(String arn) {
        return new Failure(arn, "MISSING", null);
    }

    /** Scale-in protection applies only to service-managed tasks (AWS {@code TASK_NOT_VALID}). */
    public static Failure taskNotValid(String arn) {
        return new Failure(arn, "TASK_NOT_VALID",
                "The provided task is not valid for this operation.");
    }
}
