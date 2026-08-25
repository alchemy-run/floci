package io.github.hectorvent.floci.services.eks.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class CreateAccessEntryRequest {

    @JsonProperty("principalArn")
    private String principalArn;

    @JsonProperty("kubernetesGroups")
    private List<String> kubernetesGroups;

    @JsonProperty("tags")
    private Map<String, String> tags;

    @JsonProperty("clientRequestToken")
    private String clientRequestToken;

    @JsonProperty("username")
    private String username;

    @JsonProperty("type")
    private String type;

    public CreateAccessEntryRequest() {}

    public String getPrincipalArn() { return principalArn; }
    public void setPrincipalArn(String principalArn) { this.principalArn = principalArn; }

    public List<String> getKubernetesGroups() { return kubernetesGroups; }
    public void setKubernetesGroups(List<String> kubernetesGroups) { this.kubernetesGroups = kubernetesGroups; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }

    public String getClientRequestToken() { return clientRequestToken; }
    public void setClientRequestToken(String clientRequestToken) { this.clientRequestToken = clientRequestToken; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
}
