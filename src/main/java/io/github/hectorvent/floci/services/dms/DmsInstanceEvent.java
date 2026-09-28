package io.github.hectorvent.floci.services.dms;

/**
 * The replication-instance lifecycle events Floci emits, with the DMS event identifiers and
 * categories DMS documents for them. {@code legacyCategory} is the lowercase category
 * DescribeEvents reports; {@code eventBridgeCategory} and {@code eventType} populate the
 * EventBridge {@code detail}.
 */
public enum DmsInstanceEvent {
    CREATION_STARTED("DMS-EVENT-0067", "creation", "Creation",
            "REPLICATION_INSTANCE_CREATION_STARTED", "A replication instance is being created."),
    CREATION_FINISHED("DMS-EVENT-0005", "creation", "Creation",
            "REPLICATION_INSTANCE_CREATION_FINISHED", "A replication instance has been created."),
    DELETION_STARTED("DMS-EVENT-0066", "deletion", "Deletion",
            "REPLICATION_INSTANCE_DELETION_STARTED", "The replication instance is being deleted."),
    DELETION_FINISHED("DMS-EVENT-0003", "deletion", "Deletion",
            "REPLICATION_INSTANCE_DELETION_FINISHED", "The replication instance has been deleted."),
    CLASS_CHANGE_STARTED("DMS-EVENT-0012", "configuration change", "ConfigurationChange",
            "REPLICATION_INSTANCE_CLASS_CHANGE_STARTED",
            "The replication instance class for this replication instance is being changed."),
    CLASS_CHANGE_FINISHED("DMS-EVENT-0014", "configuration change", "ConfigurationChange",
            "REPLICATION_INSTANCE_CLASS_CHANGE_FINISHED",
            "The replication instance class for this replication instance has changed."),
    STORAGE_SCALE_STARTED("DMS-EVENT-0018", "configuration change", "ConfigurationChange",
            "REPLICATION_INSTANCE_STORAGE_SCALE_STARTED",
            "The storage for the replication instance is being increased."),
    STORAGE_SCALE_FINISHED("DMS-EVENT-0017", "configuration change", "ConfigurationChange",
            "REPLICATION_INSTANCE_STORAGE_SCALE_FINISHED",
            "The storage for the replication instance has been increased."),
    MULTI_AZ_STARTED("DMS-EVENT-0024", "configuration change", "ConfigurationChange",
            "REPLICATION_INSTANCE_MULTI_AZ_CONVERSION_STARTED",
            "The replication instance is transitioning to a Multi-AZ configuration."),
    MULTI_AZ_FINISHED("DMS-EVENT-0025", "configuration change", "ConfigurationChange",
            "REPLICATION_INSTANCE_MULTI_AZ_CONVERSION_FINISHED",
            "The replication instance has finished transitioning to a Multi-AZ configuration."),
    SINGLE_AZ_STARTED("DMS-EVENT-0030", "configuration change", "ConfigurationChange",
            "REPLICATION_INSTANCE_SINGLE_AZ_CONVERSION_STARTED",
            "The replication instance is transitioning to a Single-AZ configuration."),
    SINGLE_AZ_FINISHED("DMS-EVENT-0029", "configuration change", "ConfigurationChange",
            "REPLICATION_INSTANCE_SINGLE_AZ_CONVERSION_FINISHED",
            "The replication instance has finished transitioning to a Single-AZ configuration.");

    private final String eventId;
    private final String legacyCategory;
    private final String eventBridgeCategory;
    private final String eventType;
    private final String message;

    DmsInstanceEvent(String eventId, String legacyCategory, String eventBridgeCategory, String eventType,
                     String message) {
        this.eventId = eventId;
        this.legacyCategory = legacyCategory;
        this.eventBridgeCategory = eventBridgeCategory;
        this.eventType = eventType;
        this.message = message;
    }

    String eventId() {
        return eventId;
    }

    String legacyCategory() {
        return legacyCategory;
    }

    String eventBridgeCategory() {
        return eventBridgeCategory;
    }

    String eventType() {
        return eventType;
    }

    String message() {
        return message;
    }
}
