package io.github.hectorvent.floci.services.eks.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class UpdateAccessEntryRequest {

    @JsonProperty("kubernetesGroups")
    private List<String> kubernetesGroups;

    @JsonProperty("clientRequestToken")
    private String clientRequestToken;

    @JsonProperty("username")
    private String username;

    public UpdateAccessEntryRequest() {}

    public List<String> getKubernetesGroups() { return kubernetesGroups; }
    public void setKubernetesGroups(List<String> kubernetesGroups) { this.kubernetesGroups = kubernetesGroups; }

    public String getClientRequestToken() { return clientRequestToken; }
    public void setClientRequestToken(String clientRequestToken) { this.clientRequestToken = clientRequestToken; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
}
