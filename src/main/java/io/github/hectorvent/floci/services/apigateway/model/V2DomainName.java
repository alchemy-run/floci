package io.github.hectorvent.floci.services.apigateway.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class V2DomainName {
    private String domainName;
    private String domainNameArn;
    private String routingMode;
    private String apiMappingSelectionExpression;
    private List<Map<String, Object>> domainNameConfigurations = new ArrayList<>();
    private Map<String, Object> mutualTlsAuthentication;
    private Map<String, String> tags = new HashMap<>();

    public V2DomainName() {}

    public String getDomainName() { return domainName; }
    public void setDomainName(String domainName) { this.domainName = domainName; }

    public String getDomainNameArn() { return domainNameArn; }
    public void setDomainNameArn(String domainNameArn) { this.domainNameArn = domainNameArn; }

    public String getRoutingMode() { return routingMode; }
    public void setRoutingMode(String routingMode) { this.routingMode = routingMode; }

    public String getApiMappingSelectionExpression() { return apiMappingSelectionExpression; }
    public void setApiMappingSelectionExpression(String apiMappingSelectionExpression) {
        this.apiMappingSelectionExpression = apiMappingSelectionExpression;
    }

    public List<Map<String, Object>> getDomainNameConfigurations() { return domainNameConfigurations; }
    public void setDomainNameConfigurations(List<Map<String, Object>> domainNameConfigurations) {
        this.domainNameConfigurations = domainNameConfigurations != null ? domainNameConfigurations : new ArrayList<>();
    }

    public Map<String, Object> getMutualTlsAuthentication() { return mutualTlsAuthentication; }
    public void setMutualTlsAuthentication(Map<String, Object> mutualTlsAuthentication) {
        this.mutualTlsAuthentication = mutualTlsAuthentication;
    }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? tags : new HashMap<>();
    }
}
