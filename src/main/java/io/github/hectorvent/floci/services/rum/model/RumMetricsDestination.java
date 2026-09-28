package io.github.hectorvent.floci.services.rum.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

/** An extended-metrics destination of one app monitor, together with its metric definitions. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
public class RumMetricsDestination {
    private String appMonitorName;
    private String destination;
    private String destinationArn;
    private String iamRoleArn;
    private List<RumMetricDefinition> metricDefinitions = new ArrayList<>();

    public RumMetricsDestination() {
    }

    public RumMetricsDestination(String appMonitorName, String destination, String destinationArn,
                                 String iamRoleArn, List<RumMetricDefinition> metricDefinitions) {
        this.appMonitorName = appMonitorName;
        this.destination = destination;
        this.destinationArn = destinationArn;
        this.iamRoleArn = iamRoleArn;
        setMetricDefinitions(metricDefinitions);
    }

    public String getAppMonitorName() {
        return appMonitorName;
    }

    public void setAppMonitorName(String appMonitorName) {
        this.appMonitorName = appMonitorName;
    }

    public String getDestination() {
        return destination;
    }

    public void setDestination(String destination) {
        this.destination = destination;
    }

    public String getDestinationArn() {
        return destinationArn;
    }

    public void setDestinationArn(String destinationArn) {
        this.destinationArn = destinationArn;
    }

    public String getIamRoleArn() {
        return iamRoleArn;
    }

    public void setIamRoleArn(String iamRoleArn) {
        this.iamRoleArn = iamRoleArn;
    }

    public List<RumMetricDefinition> getMetricDefinitions() {
        return new ArrayList<>(metricDefinitions);
    }

    public void setMetricDefinitions(List<RumMetricDefinition> metricDefinitions) {
        this.metricDefinitions = metricDefinitions == null ? new ArrayList<>() : new ArrayList<>(metricDefinitions);
    }
}
