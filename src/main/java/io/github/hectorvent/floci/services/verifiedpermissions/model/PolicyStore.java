package io.github.hectorvent.floci.services.verifiedpermissions.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** An Amazon Verified Permissions policy store. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class PolicyStore {

    private String policyStoreId;
    private String arn;
    private String region;
    private String validationMode;
    private String description;
    private String deletionProtection;
    private String cedarJson;
    private String schemaCreatedDate;
    private String schemaUpdatedDate;
    private List<String> namespaces = new ArrayList<>();
    private Map<String, String> tags = new LinkedHashMap<>();
    private Map<String, CedarPolicy> policies = new LinkedHashMap<>();
    private Map<String, PolicyTemplate> templates = new LinkedHashMap<>();
    private String createdDate;
    private String lastUpdatedDate;
    private String clientToken;

    public PolicyStore() {}

    public String getPolicyStoreId() {
        return policyStoreId;
    }

    public void setPolicyStoreId(String policyStoreId) {
        this.policyStoreId = policyStoreId;
    }

    public String getArn() {
        return arn;
    }

    public void setArn(String arn) {
        this.arn = arn;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getValidationMode() {
        return validationMode;
    }

    public void setValidationMode(String validationMode) {
        this.validationMode = validationMode;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getDeletionProtection() {
        return deletionProtection;
    }

    public void setDeletionProtection(String deletionProtection) {
        this.deletionProtection = deletionProtection;
    }

    public String getCedarJson() {
        return cedarJson;
    }

    public void setCedarJson(String cedarJson) {
        this.cedarJson = cedarJson;
    }

    public String getSchemaCreatedDate() {
        return schemaCreatedDate;
    }

    public void setSchemaCreatedDate(String schemaCreatedDate) {
        this.schemaCreatedDate = schemaCreatedDate;
    }

    public String getSchemaUpdatedDate() {
        return schemaUpdatedDate;
    }

    public void setSchemaUpdatedDate(String schemaUpdatedDate) {
        this.schemaUpdatedDate = schemaUpdatedDate;
    }

    public List<String> getNamespaces() {
        if (namespaces == null) {
            namespaces = new ArrayList<>();
        }
        return namespaces;
    }

    public void setNamespaces(List<String> namespaces) {
        this.namespaces = namespaces != null ? namespaces : new ArrayList<>();
    }

    public Map<String, String> getTags() {
        if (tags == null) {
            tags = new LinkedHashMap<>();
        }
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? tags : new LinkedHashMap<>();
    }

    public Map<String, CedarPolicy> getPolicies() {
        if (policies == null) {
            policies = new LinkedHashMap<>();
        }
        return policies;
    }

    public void setPolicies(Map<String, CedarPolicy> policies) {
        this.policies = policies != null ? policies : new LinkedHashMap<>();
    }

    public Map<String, PolicyTemplate> getTemplates() {
        if (templates == null) {
            templates = new LinkedHashMap<>();
        }
        return templates;
    }

    public void setTemplates(Map<String, PolicyTemplate> templates) {
        this.templates = templates != null ? templates : new LinkedHashMap<>();
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

    public String getClientToken() {
        return clientToken;
    }

    public void setClientToken(String clientToken) {
        this.clientToken = clientToken;
    }
}
