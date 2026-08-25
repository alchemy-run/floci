package io.github.hectorvent.floci.services.neptune.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.HashMap;
import java.util.Map;

@RegisterForReflection
public class NeptuneClusterParameterGroup {

    private String dbClusterParameterGroupName;
    private String dbParameterGroupFamily;
    private String description;
    private String dbClusterParameterGroupArn;
    private Map<String, String> parameters = new HashMap<>();
    private Map<String, String> tags = new HashMap<>();

    public NeptuneClusterParameterGroup() {}

    public NeptuneClusterParameterGroup(String dbClusterParameterGroupName, String dbParameterGroupFamily,
                                        String description) {
        this.dbClusterParameterGroupName = dbClusterParameterGroupName;
        this.dbParameterGroupFamily = dbParameterGroupFamily;
        this.description = description;
    }

    public String getDbClusterParameterGroupName() { return dbClusterParameterGroupName; }
    public void setDbClusterParameterGroupName(String dbClusterParameterGroupName) {
        this.dbClusterParameterGroupName = dbClusterParameterGroupName;
    }

    public String getDbParameterGroupFamily() { return dbParameterGroupFamily; }
    public void setDbParameterGroupFamily(String dbParameterGroupFamily) {
        this.dbParameterGroupFamily = dbParameterGroupFamily;
    }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Map<String, String> getParameters() { return parameters; }
    public void setParameters(Map<String, String> parameters) { this.parameters = parameters; }

    public String getDbClusterParameterGroupArn() { return dbClusterParameterGroupArn; }
    public void setDbClusterParameterGroupArn(String dbClusterParameterGroupArn) {
        this.dbClusterParameterGroupArn = dbClusterParameterGroupArn;
    }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? tags : new HashMap<>();
    }
}
