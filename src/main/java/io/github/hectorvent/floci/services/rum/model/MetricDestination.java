package io.github.hectorvent.floci.services.rum.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

/** A CloudWatch RUM metrics destination and its metric definitions. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
public class MetricDestination {
    private String destination;
    private String destinationArn;
    private String iamRoleArn;
    private List<MetricDefinition> metricDefinitions = new ArrayList<>();

    public MetricDestination() {
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

    public List<MetricDefinition> getMetricDefinitions() {
        return metricDefinitions == null ? List.of() : List.copyOf(metricDefinitions);
    }

    public void setMetricDefinitions(List<MetricDefinition> metricDefinitions) {
        this.metricDefinitions = metricDefinitions == null
                ? new ArrayList<>()
                : new ArrayList<>(metricDefinitions);
    }
}
