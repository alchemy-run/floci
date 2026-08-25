package io.github.hectorvent.floci.services.cloudwatch.metrics.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class AnomalyDetector {
    private String namespace;
    private String metricName;
    private List<Dimension> dimensions = new ArrayList<>();
    private String stat;
    private String stateValue = "PENDING_TRAINING";
    private String metricTimezone;
    private Boolean periodicSpikes;
    private String metricMathJson;
    private boolean metricMath;
    private String accountId;
    private List<ExcludedTimeRange> excludedTimeRanges = new ArrayList<>();

    public String getNamespace() { return namespace; }
    public void setNamespace(String namespace) { this.namespace = namespace; }

    public String getMetricName() { return metricName; }
    public void setMetricName(String metricName) { this.metricName = metricName; }

    public List<Dimension> getDimensions() { return dimensions; }
    public void setDimensions(List<Dimension> dimensions) {
        this.dimensions = dimensions != null ? dimensions : new ArrayList<>();
    }

    public String getStat() { return stat; }
    public void setStat(String stat) { this.stat = stat; }

    public String getStateValue() { return stateValue; }
    public void setStateValue(String stateValue) { this.stateValue = stateValue; }

    public String getMetricTimezone() { return metricTimezone; }
    public void setMetricTimezone(String metricTimezone) { this.metricTimezone = metricTimezone; }

    public Boolean getPeriodicSpikes() { return periodicSpikes; }
    public void setPeriodicSpikes(Boolean periodicSpikes) { this.periodicSpikes = periodicSpikes; }

    public String getMetricMathJson() { return metricMathJson; }
    public void setMetricMathJson(String metricMathJson) { this.metricMathJson = metricMathJson; }

    public boolean isMetricMath() { return metricMath; }
    public void setMetricMath(boolean metricMath) { this.metricMath = metricMath; }

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }

    public List<ExcludedTimeRange> getExcludedTimeRanges() { return excludedTimeRanges; }
    public void setExcludedTimeRanges(List<ExcludedTimeRange> excludedTimeRanges) {
        this.excludedTimeRanges = excludedTimeRanges != null ? excludedTimeRanges : new ArrayList<>();
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ExcludedTimeRange {
        private long startTime;
        private long endTime;

        public long getStartTime() { return startTime; }
        public void setStartTime(long startTime) { this.startTime = startTime; }

        public long getEndTime() { return endTime; }
        public void setEndTime(long endTime) { this.endTime = endTime; }
    }
}
