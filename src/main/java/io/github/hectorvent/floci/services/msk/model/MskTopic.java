package io.github.hectorvent.floci.services.msk.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class MskTopic {

    @JsonProperty("topicArn")
    private String topicArn;

    @JsonProperty("topicName")
    private String topicName;

    @JsonProperty("partitionCount")
    private int partitionCount = 1;

    @JsonProperty("replicationFactor")
    private Integer replicationFactor;

    @JsonProperty("status")
    private String status = "ACTIVE";

    public MskTopic() {}

    public MskTopic(String topicArn, String topicName, Integer partitionCount, Integer replicationFactor) {
        this.topicArn = topicArn;
        this.topicName = topicName;
        this.partitionCount = partitionCount == null ? 1 : partitionCount;
        this.replicationFactor = replicationFactor;
        this.status = "ACTIVE";
    }

    public String getTopicArn() {
        return topicArn;
    }

    public void setTopicArn(String topicArn) {
        this.topicArn = topicArn;
    }

    public String getTopicName() {
        return topicName;
    }

    public void setTopicName(String topicName) {
        this.topicName = topicName;
    }

    public int getPartitionCount() {
        return partitionCount;
    }

    public void setPartitionCount(int partitionCount) {
        this.partitionCount = partitionCount;
    }

    public Integer getReplicationFactor() {
        return replicationFactor;
    }

    public void setReplicationFactor(Integer replicationFactor) {
        this.replicationFactor = replicationFactor;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
