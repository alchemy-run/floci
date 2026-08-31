package io.github.hectorvent.floci.services.verifiedpermissions.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** A Cedar policy stored in a Verified Permissions policy store. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class CedarPolicy {

    private String policyId;
    private String policyType;
    private String description;
    private String statement;
    private String name;
    private String effect;
    private String createdDate;
    private String lastUpdatedDate;
    private String policyTemplateId;
    private String principalEntityType;
    private String principalEntityId;
    private String resourceEntityType;
    private String resourceEntityId;

    public CedarPolicy() {}

    public String getPolicyId() {
        return policyId;
    }

    public void setPolicyId(String policyId) {
        this.policyId = policyId;
    }

    public String getPolicyType() {
        return policyType;
    }

    public void setPolicyType(String policyType) {
        this.policyType = policyType;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getStatement() {
        return statement;
    }

    public void setStatement(String statement) {
        this.statement = statement;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getEffect() {
        return effect;
    }

    public void setEffect(String effect) {
        this.effect = effect;
    }

    public String getCreatedDate() {
        return createdDate;
    }

    public void setCreatedDate(String createdDate) {
        this.createdDate = createdDate;
    }

    public String getLastUpdatedDate() {
        return lastUpdatedDate;
    }

    public void setLastUpdatedDate(String lastUpdatedDate) {
        this.lastUpdatedDate = lastUpdatedDate;
    }

    public String getPolicyTemplateId() {
        return policyTemplateId;
    }

    public void setPolicyTemplateId(String policyTemplateId) {
        this.policyTemplateId = policyTemplateId;
    }

    public String getPrincipalEntityType() {
        return principalEntityType;
    }

    public void setPrincipalEntityType(String principalEntityType) {
        this.principalEntityType = principalEntityType;
    }

    public String getPrincipalEntityId() {
        return principalEntityId;
    }

    public void setPrincipalEntityId(String principalEntityId) {
        this.principalEntityId = principalEntityId;
    }

    public String getResourceEntityType() {
        return resourceEntityType;
    }

    public void setResourceEntityType(String resourceEntityType) {
        this.resourceEntityType = resourceEntityType;
    }

    public String getResourceEntityId() {
        return resourceEntityId;
    }

    public void setResourceEntityId(String resourceEntityId) {
        this.resourceEntityId = resourceEntityId;
    }
}
