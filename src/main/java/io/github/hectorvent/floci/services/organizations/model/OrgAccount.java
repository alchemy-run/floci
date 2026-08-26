package io.github.hectorvent.floci.services.organizations.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class OrgAccount {

    private String id;
    private String arn;
    private String email;
    private String name;
    private String status;
    private String state;
    private String joinedMethod;
    private long joinedTimestamp;
    private boolean management;

    public OrgAccount() {
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

    public boolean isManagement() {
        return management;
    }

    public void setManagement(boolean management) {
        this.management = management;
    }
}
