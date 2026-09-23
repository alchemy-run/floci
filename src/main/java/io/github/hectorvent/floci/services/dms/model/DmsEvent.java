package io.github.hectorvent.floci.services.dms.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/**
 * One entry of the DescribeEvents history. {@code id} sorts by time: it is the zero-padded epoch
 * millisecond timestamp followed by a random suffix, so it doubles as the pagination cursor.
 */
@RegisterForReflection
public record DmsEvent(String id, String sourceIdentifier, String sourceType, String message,
                       List<String> eventCategories, long dateMillis) {

    public DmsEvent {
        eventCategories = eventCategories != null ? List.copyOf(eventCategories) : List.of();
    }
}
