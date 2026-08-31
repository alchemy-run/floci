package io.github.hectorvent.floci.services.cloudtrail;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.hectorvent.floci.services.cloudtrail.model.AdvancedEventSelector;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record EventDataStoreEntry(
        String id,
        String region,
        String arn,
        String name,
        String status,
        List<AdvancedEventSelector> advancedEventSelectors,
        boolean multiRegionEnabled,
        boolean organizationEnabled,
        int retentionPeriod,
        boolean terminationProtectionEnabled,
        String billingMode,
        String kmsKeyId,
        long createdTimestamp,
        long updatedTimestamp,
        Map<String, String> tags) {

    public static final String STATUS_CREATED = "CREATED";
    public static final String STATUS_ENABLED = "ENABLED";
    public static final String STATUS_PENDING_DELETION = "PENDING_DELETION";
    public static final String STATUS_STARTING_INGESTION = "STARTING_INGESTION";
    public static final String STATUS_STOPPING_INGESTION = "STOPPING_INGESTION";
    public static final String STATUS_STOPPED_INGESTION = "STOPPED_INGESTION";

    public EventDataStoreEntry {
        if (advancedEventSelectors == null) {
            advancedEventSelectors = List.of();
        }
        if (tags == null) {
            tags = Map.of();
        }
    }

    public String storageKey() {
        return region + ":" + id;
    }

    public boolean pendingDeletion() {
        return STATUS_PENDING_DELETION.equals(status);
    }

    public EventDataStoreEntry withStatus(String newStatus, long now) {
        return new EventDataStoreEntry(id, region, arn, name, newStatus, advancedEventSelectors,
                multiRegionEnabled, organizationEnabled, retentionPeriod, terminationProtectionEnabled,
                billingMode, kmsKeyId, createdTimestamp, now, tags);
    }

    public EventDataStoreEntry withTags(Map<String, String> updated) {
        return new EventDataStoreEntry(id, region, arn, name, status, advancedEventSelectors,
                multiRegionEnabled, organizationEnabled, retentionPeriod, terminationProtectionEnabled,
                billingMode, kmsKeyId, createdTimestamp, updatedTimestamp, updated);
    }

    public EventDataStoreEntry withUpdates(String newName,
                                           List<AdvancedEventSelector> newSelectors,
                                           Boolean newMultiRegion,
                                           Boolean newOrganization,
                                           Integer newRetention,
                                           Boolean newTerminationProtection,
                                           String newKmsKeyId,
                                           String newBillingMode,
                                           long now) {
        return new EventDataStoreEntry(
                id, region, arn,
                newName != null ? newName : name,
                status,
                newSelectors != null ? newSelectors : advancedEventSelectors,
                newMultiRegion != null ? newMultiRegion : multiRegionEnabled,
                newOrganization != null ? newOrganization : organizationEnabled,
                newRetention != null ? newRetention : retentionPeriod,
                newTerminationProtection != null ? newTerminationProtection : terminationProtectionEnabled,
                newBillingMode != null ? newBillingMode : billingMode,
                newKmsKeyId != null ? newKmsKeyId : kmsKeyId,
                createdTimestamp, now, tags);
    }
}
