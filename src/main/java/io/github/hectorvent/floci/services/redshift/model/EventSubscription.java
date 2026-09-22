package io.github.hectorvent.floci.services.redshift.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Map;

@RegisterForReflection
public record EventSubscription(String name, String region, String customerAwsId, String snsTopicArn,
                                String status, String creationTime, String sourceType, List<String> sourceIds,
                                List<String> eventCategories, String severity, boolean enabled,
                                Map<String, String> tags) {
    public EventSubscription {
        sourceIds = List.copyOf(sourceIds);
        eventCategories = List.copyOf(eventCategories);
        tags = Map.copyOf(tags);
    }

    public EventSubscription withTags(Map<String, String> updated) {
        return new EventSubscription(name, region, customerAwsId, snsTopicArn, status, creationTime,
                sourceType, sourceIds, eventCategories, severity, enabled, updated);
    }

    public EventSubscription withStatus(String updated) {
        return new EventSubscription(name, region, customerAwsId, snsTopicArn, updated, creationTime,
                sourceType, sourceIds, eventCategories, severity, enabled, tags);
    }
}
