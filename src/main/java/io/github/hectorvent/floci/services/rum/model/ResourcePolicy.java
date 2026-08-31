package io.github.hectorvent.floci.services.rum.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** A CloudWatch RUM app-monitor resource-based policy. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
public class ResourcePolicy {
    private String policyDocument;
    private String policyRevisionId;

    public ResourcePolicy() {
    }

    public ResourcePolicy(String policyDocument, String policyRevisionId) {
        this.policyDocument = policyDocument;
        this.policyRevisionId = policyRevisionId;
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
}
