package io.github.hectorvent.floci.services.b2bi.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** An AWS B2BI customer profile. Wire names are camelCase awsJson1_0. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class B2biProfile {

    private String profileId;
    private String profileArn;
    private String name;
    private String businessName;
    private String phone;
    private String email;
    private String logging;
    private String logGroupName;
    private String createdAt;
    private String modifiedAt;
    private Map<String, String> tags = new LinkedHashMap<>();

    public B2biProfile() {}

    public String getProfileId() {
        return profileId;
    }

    public void setProfileId(String profileId) {
        this.profileId = profileId;
    }

    public String getProfileArn() {
        return profileArn;
    }

    public void setProfileArn(String profileArn) {
        this.profileArn = profileArn;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getBusinessName() {
        return businessName;
    }

    public void setBusinessName(String businessName) {
        this.businessName = businessName;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getLogging() {
        return logging;
    }

    public void setLogging(String logging) {
        this.logging = logging;
    }

    public String getLogGroupName() {
        return logGroupName;
    }

    public void setLogGroupName(String logGroupName) {
        this.logGroupName = logGroupName;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public String getModifiedAt() {
        return modifiedAt;
    }

    public void setModifiedAt(String modifiedAt) {
        this.modifiedAt = modifiedAt;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? tags : new LinkedHashMap<>();
    }
}
