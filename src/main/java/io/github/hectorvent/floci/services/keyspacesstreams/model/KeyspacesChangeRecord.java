package io.github.hectorvent.floci.services.keyspacesstreams.model;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * One CDC change record in a Keyspaces stream shard.
 */
public class KeyspacesChangeRecord {

    private String eventVersion = "1.0";
    private long createdAt;
    private String origin = "USER";
    private JsonNode partitionKeys;
    private JsonNode clusteringKeys;
    private JsonNode newImage;
    private JsonNode oldImage;
    private String sequenceNumber;

    public String getEventVersion() {
        return eventVersion;
    }

    public void setEventVersion(String eventVersion) {
        this.eventVersion = eventVersion;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public String getOrigin() {
        return origin;
    }

    public void setOrigin(String origin) {
        this.origin = origin;
    }

    public JsonNode getPartitionKeys() {
        return partitionKeys;
    }

    public void setPartitionKeys(JsonNode partitionKeys) {
        this.partitionKeys = partitionKeys;
    }

    public JsonNode getClusteringKeys() {
        return clusteringKeys;
    }

    public void setClusteringKeys(JsonNode clusteringKeys) {
        this.clusteringKeys = clusteringKeys;
    }

    public JsonNode getNewImage() {
        return newImage;
    }

    public void setNewImage(JsonNode newImage) {
        this.newImage = newImage;
    }

    public JsonNode getOldImage() {
        return oldImage;
    }

    public void setOldImage(JsonNode oldImage) {
        this.oldImage = oldImage;
    }

    public String getSequenceNumber() {
        return sequenceNumber;
    }

    public void setSequenceNumber(String sequenceNumber) {
        this.sequenceNumber = sequenceNumber;
    }
}
