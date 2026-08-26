package io.github.hectorvent.floci.services.organizations.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Member (or management) account inside an AWS Organization.
 */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class OrganizationAccount {

    private String id;
    private String arn;
    private String email;
    private String name;
    private String status;
    private String state;
    private String joinedMethod;
    private long joinedTimestamp;
    private String parentId;
    private String parentType;
    private Map<String, String> tags = new LinkedHashMap<>();

    public OrganizationAccount() {
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

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getJoinedMethod() {
        return joinedMethod;
    }

    public void setJoinedMethod(String joinedMethod) {
        this.joinedMethod = joinedMethod;
    }

    public long getJoinedTimestamp() {
        return joinedTimestamp;
    }

    public void setJoinedTimestamp(long joinedTimestamp) {
        this.joinedTimestamp = joinedTimestamp;
    }

    public String getParentId() {
        return parentId;
    }

    public void setParentId(String parentId) {
        this.parentId = parentId;
    }

    public String getParentType() {
        return parentType;
    }

    public void setParentType(String parentType) {
        this.parentType = parentType;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? tags : new LinkedHashMap<>();
    }
}
