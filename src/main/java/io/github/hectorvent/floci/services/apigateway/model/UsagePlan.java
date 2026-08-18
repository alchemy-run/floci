package io.github.hectorvent.floci.services.apigateway.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class UsagePlan {
    private String id;
    private String name;
    private String description;
    private List<ApiStage> apiStages = new ArrayList<>();
    private ThrottleSettings throttle;
    private QuotaSettings quota;
    private Map<String, String> tags = new HashMap<>();

    public UsagePlan() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public List<ApiStage> getApiStages() { return apiStages; }
    public void setApiStages(List<ApiStage> apiStages) { this.apiStages = apiStages != null ? apiStages : new ArrayList<>(); }

    public ThrottleSettings getThrottle() { return throttle; }
    public void setThrottle(ThrottleSettings throttle) { this.throttle = throttle; }

    public QuotaSettings getQuota() { return quota; }
    public void setQuota(QuotaSettings quota) { this.quota = quota; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags != null ? tags : new HashMap<>(); }

    @RegisterForReflection
    public record ApiStage(String apiId, String stage) {}
}
