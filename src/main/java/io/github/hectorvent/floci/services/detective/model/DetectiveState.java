package io.github.hectorvent.floci.services.detective.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class DetectiveState {
    private String adminAccountId;
    private boolean graph;
    private boolean autoEnable;
    private String graphArn;
    private String createdTime;
    private Map<String, String> tags = new LinkedHashMap<>();
    private Map<String, DetectiveMember> members = new LinkedHashMap<>();
    private String coreCollectionStartTime;
    private String coreIngestState;
    private Map<String, String> coreIngestStateChanges = new LinkedHashMap<>();
    private Map<String, String> coreEvents = new LinkedHashMap<>();

    public DetectiveState() {
    }

    public String getGraphArn() { return graphArn; }
    public void setGraphArn(String graphArn) { this.graphArn = graphArn; }
    public String getCreatedTime() { return createdTime; }
    public void setCreatedTime(String createdTime) { this.createdTime = createdTime; }
    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }

    public String getAdminAccountId() { return adminAccountId; }
    public void setAdminAccountId(String adminAccountId) { this.adminAccountId = adminAccountId; }
    public boolean isGraph() { return graph; }
    public void setGraph(boolean graph) { this.graph = graph; }
    public boolean isAutoEnable() { return autoEnable; }
    public void setAutoEnable(boolean autoEnable) { this.autoEnable = autoEnable; }
    public Map<String, DetectiveMember> getMembers() { return members; }
    public void setMembers(Map<String, DetectiveMember> members) { this.members = members; }
    public String getCoreCollectionStartTime() { return coreCollectionStartTime; }
    public void setCoreCollectionStartTime(String coreCollectionStartTime) { this.coreCollectionStartTime = coreCollectionStartTime; }
    public String getCoreIngestState() { return coreIngestState; }
    public void setCoreIngestState(String coreIngestState) { this.coreIngestState = coreIngestState; }
    public Map<String, String> getCoreIngestStateChanges() { return coreIngestStateChanges; }
    public void setCoreIngestStateChanges(Map<String, String> coreIngestStateChanges) { this.coreIngestStateChanges = coreIngestStateChanges; }
    public Map<String, String> getCoreEvents() { return coreEvents; }
    public void setCoreEvents(Map<String, String> coreEvents) { this.coreEvents = coreEvents; }
}
