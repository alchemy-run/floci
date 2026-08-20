package io.github.hectorvent.floci.services.apigateway.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.HashMap;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class GatewayResponse {
    private String responseType;
    private String statusCode;
    private Map<String, String> responseParameters = new HashMap<>();
    private Map<String, String> responseTemplates = new HashMap<>();
    private boolean defaultResponse;

    public GatewayResponse() {}

    public String getResponseType() { return responseType; }
    public void setResponseType(String responseType) { this.responseType = responseType; }

    public String getStatusCode() { return statusCode; }
    public void setStatusCode(String statusCode) { this.statusCode = statusCode; }

    public Map<String, String> getResponseParameters() { return responseParameters; }
    public void setResponseParameters(Map<String, String> responseParameters) {
        this.responseParameters = responseParameters != null ? responseParameters : new HashMap<>();
    }

    public Map<String, String> getResponseTemplates() { return responseTemplates; }
    public void setResponseTemplates(Map<String, String> responseTemplates) {
        this.responseTemplates = responseTemplates != null ? responseTemplates : new HashMap<>();
    }

    public boolean isDefaultResponse() { return defaultResponse; }
    public void setDefaultResponse(boolean defaultResponse) { this.defaultResponse = defaultResponse; }
}
