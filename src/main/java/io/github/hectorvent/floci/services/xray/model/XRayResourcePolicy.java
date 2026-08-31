package io.github.hectorvent.floci.services.xray.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/** Account-level X-Ray resource policy. */
@RegisterForReflection
public class XRayResourcePolicy {
    private String policyName;
    private String policyDocument;
    private String policyRevisionId;
    private double lastUpdatedTime;

    public XRayResourcePolicy() {
    }

    public String getPolicyName() {
        return policyName;
    }

    public void setPolicyName(String policyName) {
        this.policyName = policyName;
    }

    public String getPolicyDocument() {
        return policyDocument;
    }

    public void setPolicyDocument(String policyDocument) {
        this.policyDocument = policyDocument;
    }

    public String getPolicyRevisionId() {
        return policyRevisionId;
    }

    public void setPolicyRevisionId(String policyRevisionId) {
        this.policyRevisionId = policyRevisionId;
    }

    public double getLastUpdatedTime() {
        return lastUpdatedTime;
    }

    public void setLastUpdatedTime(double lastUpdatedTime) {
        this.lastUpdatedTime = lastUpdatedTime;
    }
}
