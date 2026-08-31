package io.github.hectorvent.floci.services.cloudwatch.metrics.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class MetricStream {
    private String name;
    private String arn;
    private String firehoseArn;
    private String roleArn;
    private String outputFormat;
    private String state = "running";
    private long creationDate;
    private long lastUpdateDate;
    private Boolean includeLinkedAccountsMetrics;
    private List<Filter> includeFilters = new ArrayList<>();
    private List<Filter> excludeFilters = new ArrayList<>();
    private List<StatisticsConfiguration> statisticsConfigurations = new ArrayList<>();
    private Map<String, String> tags = new LinkedHashMap<>();

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public String getFirehoseArn() { return firehoseArn; }
    public void setFirehoseArn(String firehoseArn) { this.firehoseArn = firehoseArn; }

    public String getRoleArn() { return roleArn; }
    public void setRoleArn(String roleArn) { this.roleArn = roleArn; }

    public String getOutputFormat() { return outputFormat; }
    public void setOutputFormat(String outputFormat) { this.outputFormat = outputFormat; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public long getCreationDate() { return creationDate; }
    public void setCreationDate(long creationDate) { this.creationDate = creationDate; }

    public long getLastUpdateDate() { return lastUpdateDate; }
    public void setLastUpdateDate(long lastUpdateDate) { this.lastUpdateDate = lastUpdateDate; }

    public Boolean getIncludeLinkedAccountsMetrics() { return includeLinkedAccountsMetrics; }
    public void setIncludeLinkedAccountsMetrics(Boolean includeLinkedAccountsMetrics) {
        this.includeLinkedAccountsMetrics = includeLinkedAccountsMetrics;
    }

    public List<Filter> getIncludeFilters() { return includeFilters; }
    public void setIncludeFilters(List<Filter> includeFilters) {
        this.includeFilters = includeFilters != null ? includeFilters : new ArrayList<>();
    }

    public List<Filter> getExcludeFilters() { return excludeFilters; }
    public void setExcludeFilters(List<Filter> excludeFilters) {
        this.excludeFilters = excludeFilters != null ? excludeFilters : new ArrayList<>();
    }

    public List<StatisticsConfiguration> getStatisticsConfigurations() { return statisticsConfigurations; }
    public void setStatisticsConfigurations(List<StatisticsConfiguration> statisticsConfigurations) {
        this.statisticsConfigurations = statisticsConfigurations != null
                ? statisticsConfigurations : new ArrayList<>();
    }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? tags : new LinkedHashMap<>();
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Filter {
        private String namespace;
        private List<String> metricNames = new ArrayList<>();

        public String getNamespace() { return namespace; }
        public void setNamespace(String namespace) { this.namespace = namespace; }

        public List<String> getMetricNames() { return metricNames; }
        public void setMetricNames(List<String> metricNames) {
            this.metricNames = metricNames != null ? metricNames : new ArrayList<>();
        }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class StatisticsMetric {
        private String namespace;
        private String metricName;

        public String getNamespace() { return namespace; }
        public void setNamespace(String namespace) { this.namespace = namespace; }

        public String getMetricName() { return metricName; }
        public void setMetricName(String metricName) { this.metricName = metricName; }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class StatisticsConfiguration {
        private List<StatisticsMetric> includeMetrics = new ArrayList<>();
        private List<String> additionalStatistics = new ArrayList<>();

        public List<StatisticsMetric> getIncludeMetrics() { return includeMetrics; }
        public void setIncludeMetrics(List<StatisticsMetric> includeMetrics) {
            this.includeMetrics = includeMetrics != null ? includeMetrics : new ArrayList<>();
        }

        public List<String> getAdditionalStatistics() { return additionalStatistics; }
        public void setAdditionalStatistics(List<String> additionalStatistics) {
            this.additionalStatistics = additionalStatistics != null
                    ? additionalStatistics : new ArrayList<>();
        }
    }
}
