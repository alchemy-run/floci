package io.github.hectorvent.floci.services.cloudwatch.logs.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class MetricFilter {

    private String filterName;
    private String logGroupName;
    private String filterPattern;
    private List<Map<String, Object>> metricTransformations = new ArrayList<>();
    private long creationTime;

    public MetricFilter() {}

    public String getFilterName() { return filterName; }
    public void setFilterName(String filterName) { this.filterName = filterName; }

    public String getLogGroupName() { return logGroupName; }
    public void setLogGroupName(String logGroupName) { this.logGroupName = logGroupName; }

    public String getFilterPattern() { return filterPattern; }
    public void setFilterPattern(String filterPattern) { this.filterPattern = filterPattern; }

    public List<Map<String, Object>> getMetricTransformations() { return metricTransformations; }
    public void setMetricTransformations(List<Map<String, Object>> metricTransformations) {
        this.metricTransformations = metricTransformations != null ? metricTransformations : new ArrayList<>();
    }

    public long getCreationTime() { return creationTime; }
    public void setCreationTime(long creationTime) { this.creationTime = creationTime; }
}
