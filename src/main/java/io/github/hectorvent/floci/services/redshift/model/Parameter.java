package io.github.hectorvent.floci.services.redshift.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class Parameter {
    private String parameterName;
    private String parameterValue;
    private String description;
    private String dataType;
    private String source;

    public Parameter() {}

    public Parameter(String parameterName, String parameterValue) {
        this.parameterName = parameterName;
        this.parameterValue = parameterValue;
    }

    public Parameter(String parameterName, String parameterValue, String description, String dataType) {
        this.parameterName = parameterName;
        this.parameterValue = parameterValue;
        this.description = description;
        this.dataType = dataType;
    }

    public String getSource() {
        if (source != null) {
            return source;
        }
        // Older persisted parameters have no source field.
        return ClusterParameterGroup.defaultParameters().stream().anyMatch(parameter ->
                parameter.getParameterName().equals(parameterName)
                        && parameter.getParameterValue().equals(parameterValue)) ? "engine-default" : "user";
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getParameterName() {
        return parameterName;
    }

    public void setParameterName(String parameterName) {
        this.parameterName = parameterName;
    }

    public String getParameterValue() {
        return parameterValue;
    }

    public void setParameterValue(String parameterValue) {
        this.parameterValue = parameterValue;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getDataType() {
        return dataType;
    }

    public void setDataType(String dataType) {
        this.dataType = dataType;
    }
}
