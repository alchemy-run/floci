package io.github.hectorvent.floci.services.cloudtrail;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.hectorvent.floci.services.cloudtrail.model.AdvancedEventSelector;
import io.github.hectorvent.floci.services.cloudtrail.model.EventSelector;
import io.github.hectorvent.floci.services.cloudtrail.model.InsightSelector;
import io.github.hectorvent.floci.services.cloudtrail.model.Trail;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record CloudTrailEntry(
        Trail trail,
        List<EventSelector> selectors,
        List<AdvancedEventSelector> advancedSelectors,
        List<InsightSelector> insightSelectors,
        boolean logging,
        Long startLoggingTime,
        Long stopLoggingTime,
        Map<String, String> tags) {

    public CloudTrailEntry {
        if (selectors == null) {
            selectors = List.of();
        }
        if (advancedSelectors == null) {
            advancedSelectors = List.of();
        }
        if (insightSelectors == null) {
            insightSelectors = List.of();
        }
        if (tags == null) {
            tags = Map.of();
        }
    }

    public CloudTrailEntry withTrail(Trail updated) {
        return copy(updated, selectors, advancedSelectors, insightSelectors, logging,
                startLoggingTime, stopLoggingTime, tags);
    }

    public CloudTrailEntry withSelectors(List<EventSelector> updated, boolean hasCustomSelectors) {
        // Basic and advanced selectors are mutually exclusive.
        Trail updatedTrail = copyTrail(hasCustomSelectors, trail.hasInsightSelectors());
        return copy(updatedTrail, updated, List.of(), insightSelectors, logging,
                startLoggingTime, stopLoggingTime, tags);
    }

    public CloudTrailEntry withAdvancedSelectors(List<AdvancedEventSelector> updated) {
        boolean hasCustom = updated != null && !updated.isEmpty();
        Trail updatedTrail = copyTrail(hasCustom, trail.hasInsightSelectors());
        return copy(updatedTrail, List.of(), updated == null ? List.of() : updated, insightSelectors,
                logging, startLoggingTime, stopLoggingTime, tags);
    }

    public CloudTrailEntry withInsightSelectors(List<InsightSelector> updated) {
        boolean enabled = updated != null && !updated.isEmpty();
        Trail updatedTrail = copyTrail(trail.hasCustomEventSelectors(), enabled);
        return copy(updatedTrail, selectors, advancedSelectors,
                updated == null ? List.of() : updated, logging, startLoggingTime, stopLoggingTime, tags);
    }

    public CloudTrailEntry startLogging(long time) {
        return copy(trail, selectors, advancedSelectors, insightSelectors, true,
                time, stopLoggingTime, tags);
    }

    public CloudTrailEntry stopLogging(long time) {
        return copy(trail, selectors, advancedSelectors, insightSelectors, false,
                startLoggingTime, time, tags);
    }

    public CloudTrailEntry withTags(Map<String, String> updated) {
        return copy(trail, selectors, advancedSelectors, insightSelectors, logging,
                startLoggingTime, stopLoggingTime, updated);
    }

    private CloudTrailEntry copy(Trail trail,
                                 List<EventSelector> selectors,
                                 List<AdvancedEventSelector> advancedSelectors,
                                 List<InsightSelector> insightSelectors,
                                 boolean logging,
                                 Long startLoggingTime,
                                 Long stopLoggingTime,
                                 Map<String, String> tags) {
        return new CloudTrailEntry(trail, selectors, advancedSelectors, insightSelectors,
                logging, startLoggingTime, stopLoggingTime, tags);
    }

    private Trail copyTrail(boolean hasCustomSelectors, boolean hasInsightSelectors) {
        return new Trail(
                trail.name(), trail.trailArn(), trail.s3BucketName(), trail.s3KeyPrefix(),
                trail.snsTopicArn(), trail.includeGlobalServiceEvents(), trail.isMultiRegionTrail(),
                trail.homeRegion(), trail.logFileValidationEnabled(), hasCustomSelectors,
                hasInsightSelectors, trail.isOrganizationTrail());
    }
}
