package io.github.hectorvent.floci.services.redshift.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

@RegisterForReflection
public class ClusterParameterGroup {

    /** Physical parameter group name. */
    private String parameterGroupName;
    private String parameterGroupFamily;
    private String description;
    private Map<String, String> parameters = new LinkedHashMap<>();
    private Map<String, String> tags = new LinkedHashMap<>();

    public ClusterParameterGroup() {}

    public ClusterParameterGroup(String parameterGroupName, String parameterGroupFamily, String description) {
        this.parameterGroupName = parameterGroupName;
        this.parameterGroupFamily = parameterGroupFamily;
        this.description = description;
    }

    public String getParameterGroupName() {
        return parameterGroupName;
    }

    public void setParameterGroupName(String parameterGroupName) {
        this.parameterGroupName = parameterGroupName;
    }

    public String getParameterGroupFamily() {
        return parameterGroupFamily;
    }

    public void setParameterGroupFamily(String parameterGroupFamily) {
        this.parameterGroupFamily = parameterGroupFamily;
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

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? new LinkedHashMap<>(tags) : new LinkedHashMap<>();
    }
}
