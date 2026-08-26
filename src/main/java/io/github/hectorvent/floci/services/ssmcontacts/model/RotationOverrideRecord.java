package io.github.hectorvent.floci.services.ssmcontacts.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class RotationOverrideRecord {

    private String rotationOverrideId;
    private String rotationArn;
    private List<String> newContactIds = new ArrayList<>();
    private long startTime;
    private long endTime;
    private long createTime;

    public RotationOverrideRecord() {
    }

    public String getRotationOverrideId() {
        return rotationOverrideId;
    }

    public void setRotationOverrideId(String rotationOverrideId) {
        this.rotationOverrideId = rotationOverrideId;
    }

    public String getRotationArn() {
        return rotationArn;
    }

    public void setRotationArn(String rotationArn) {
        this.rotationArn = rotationArn;
    }

    public List<String> getNewContactIds() {
        return newContactIds;
    }

    public void setNewContactIds(List<String> newContactIds) {
        this.newContactIds = newContactIds != null ? newContactIds : new ArrayList<>();
    }

    public long getStartTime() {
        return startTime;
    }

    public void setStartTime(long startTime) {
        this.startTime = startTime;
    }

    public long getEndTime() {
        return endTime;
    }

    public void setEndTime(long endTime) {
        this.endTime = endTime;
    }

    public long getCreateTime() {
        return createTime;
    }

    public void setCreateTime(long createTime) {
        this.createTime = createTime;
    }
}
