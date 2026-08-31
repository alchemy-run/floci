package io.github.hectorvent.floci.services.ce.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Cost Explorer cost-anomaly monitor.
 *
 * @see <a href="https://docs.aws.amazon.com/aws-cost-management/latest/APIReference/API_AnomalyMonitor.html">AnomalyMonitor</a>
 */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class AnomalyMonitor {

    private String monitorArn;
    private String monitorName;
    private String creationDate;
    private String lastUpdatedDate;
    private String lastEvaluatedDate;
    private String monitorType;
    private String monitorDimension;
    private JsonNode monitorSpecification;
    private int dimensionalValueCount;
    private Map<String, String> tags = new LinkedHashMap<>();

    public AnomalyMonitor() {
    }

    public String getMonitorArn() {
        return monitorArn;
    }

    public void setMonitorArn(String monitorArn) {
        this.monitorArn = monitorArn;
    }

    public String getMonitorName() {
        return monitorName;
    }

    public void setMonitorName(String monitorName) {
        this.monitorName = monitorName;
    }

    public String getCreationDate() {
        return creationDate;
    }

    public void setCreationDate(String creationDate) {
        this.creationDate = creationDate;
    }

    public String getLastUpdatedDate() {
        return lastUpdatedDate;
    }

    public void setLastUpdatedDate(String lastUpdatedDate) {
        this.lastUpdatedDate = lastUpdatedDate;
    }

    public String getLastEvaluatedDate() {
        return lastEvaluatedDate;
    }

    public void setLastEvaluatedDate(String lastEvaluatedDate) {
        this.lastEvaluatedDate = lastEvaluatedDate;
    }

    public String getMonitorType() {
        return monitorType;
    }

    public void setMonitorType(String monitorType) {
        this.monitorType = monitorType;
    }

    public String getMonitorDimension() {
        return monitorDimension;
    }

    public void setMonitorDimension(String monitorDimension) {
        this.monitorDimension = monitorDimension;
    }

    public JsonNode getMonitorSpecification() {
        return monitorSpecification;
    }

    public void setMonitorSpecification(JsonNode monitorSpecification) {
        this.monitorSpecification = monitorSpecification;
    }

    public int getDimensionalValueCount() {
        return dimensionalValueCount;
    }

    public void setDimensionalValueCount(int dimensionalValueCount) {
        this.dimensionalValueCount = dimensionalValueCount;
    }

    public Map<String, String> getTags() {
        if (tags == null) {
            tags = new LinkedHashMap<>();
        }
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : tags;
    }

    @JsonIgnore
    public Map<String, String> getResourceTags() {
        return getTags();
    }

    @JsonIgnore
    public void setResourceTags(Map<String, String> resourceTags) {
        setTags(resourceTags);
    }
}
