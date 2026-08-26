package io.github.hectorvent.floci.services.organizations.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class Organization {

    private String id;
    private String arn;
    private String featureSet;
    private String masterAccountId;
    private String masterAccountArn;
    private String masterAccountEmail;
    private String rootId;
    private long createdAt;

    public Organization() {
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

    public String getFeatureSet() {
        return featureSet;
    }

    public void setFeatureSet(String featureSet) {
        this.featureSet = featureSet;
    }

    public String getMasterAccountId() {
        return masterAccountId;
    }

    public void setMasterAccountId(String masterAccountId) {
        this.masterAccountId = masterAccountId;
    }

    public String getMasterAccountArn() {
        return masterAccountArn;
    }

    public void setMasterAccountArn(String masterAccountArn) {
        this.masterAccountArn = masterAccountArn;
    }

    public String getMasterAccountEmail() {
        return masterAccountEmail;
    }

    public void setMasterAccountEmail(String masterAccountEmail) {
        this.masterAccountEmail = masterAccountEmail;
    }

    public String getRootId() {
        return rootId;
    }

    public void setRootId(String rootId) {
        this.rootId = rootId;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }
}
