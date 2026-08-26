package io.github.hectorvent.floci.services.organizations.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class OrgHandshake {

    private String id;
    private String arn;
    private String state;
    private String action;
    private long requestedTimestamp;
    private long expirationTimestamp;
    private List<Map<String, String>> parties = new ArrayList<>();
    private List<Map<String, String>> resources = new ArrayList<>();

    public OrgHandshake() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getArn() {
        return arn;
    }

    public void setArn(String arn) {
        this.arn = arn;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public long getRequestedTimestamp() {
        return requestedTimestamp;
    }

    public void setRequestedTimestamp(long requestedTimestamp) {
        this.requestedTimestamp = requestedTimestamp;
    }

    public long getExpirationTimestamp() {
        return expirationTimestamp;
    }

    public void setExpirationTimestamp(long expirationTimestamp) {
        this.expirationTimestamp = expirationTimestamp;
    }

    public List<Map<String, String>> getParties() {
        return parties;
    }

    public void setParties(List<Map<String, String>> parties) {
        this.parties = parties != null ? parties : new ArrayList<>();
    }

    public List<Map<String, String>> getResources() {
        return resources;
    }

    public void setResources(List<Map<String, String>> resources) {
        this.resources = resources != null ? resources : new ArrayList<>();
    }
}
