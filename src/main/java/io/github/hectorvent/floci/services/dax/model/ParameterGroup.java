package io.github.hectorvent.floci.services.dax.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class ParameterGroup {

    private String parameterGroupName;
    private String description;
    private Map<String, String> parameters = new LinkedHashMap<>();
    private Map<String, String> sources = new LinkedHashMap<>();

    public ParameterGroup() {
    }

    public String getParameterGroupName() {
        return parameterGroupName;
    }

    public void setParameterGroupName(String parameterGroupName) {
        this.parameterGroupName = parameterGroupName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Map<String, String> getParameters() {
        return parameters;
    }

    public void setParameters(Map<String, String> parameters) {
        this.parameters = parameters != null ? parameters : new LinkedHashMap<>();
    }

    public Map<String, String> getSources() {
        return sources;
    }

    public void setSources(Map<String, String> sources) {
        this.sources = sources != null ? sources : new LinkedHashMap<>();
    }
}
