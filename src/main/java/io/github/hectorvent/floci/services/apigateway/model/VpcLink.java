package io.github.hectorvent.floci.services.apigateway.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** API Gateway v1 VPC Link. Floci has no real VPC so it is AVAILABLE immediately. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class VpcLink {
    private String id;
    private String name;
    private String description;
    private List<String> targetArns = new ArrayList<>();
    private String status = "AVAILABLE";
    private String statusMessage;
    private Map<String, String> tags = new HashMap<>();

    public VpcLink() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public List<String> getTargetArns() { return targetArns; }
    public void setTargetArns(List<String> targetArns) {
        this.targetArns = targetArns != null ? targetArns : new ArrayList<>();
    }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getStatusMessage() { return statusMessage; }
    public void setStatusMessage(String statusMessage) { this.statusMessage = statusMessage; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? tags : new HashMap<>();
    }
}
