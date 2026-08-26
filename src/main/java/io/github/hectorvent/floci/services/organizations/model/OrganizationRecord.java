package io.github.hectorvent.floci.services.organizations.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Singleton AWS Organization plus the root that owns member accounts.
 */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class OrganizationRecord {

    private String id;
    private String arn;
    private String featureSet;
    private String masterAccountId;
    private String masterAccountArn;
    private String masterAccountEmail;
    private String rootId;
    private int nextAccountSeq = 1;
    private Map<String, String> rootTags = new LinkedHashMap<>();
    private Map<String, Long> enabledServicePrincipals = new LinkedHashMap<>();

    public OrganizationRecord() {
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

    public Map<String, String> getRootTags() {
        if (rootTags == null) {
            rootTags = new LinkedHashMap<>();
        }
        return rootTags;
    }

    public void setRootTags(Map<String, String> rootTags) {
        this.rootTags = rootTags != null ? rootTags : new LinkedHashMap<>();
    }

    public int getNextAccountSeq() {
        return nextAccountSeq;
    }

    public void setNextAccountSeq(int nextAccountSeq) {
        this.nextAccountSeq = nextAccountSeq;
    }

    public Map<String, Long> getEnabledServicePrincipals() {
        if (enabledServicePrincipals == null) {
            enabledServicePrincipals = new LinkedHashMap<>();
        }
        return enabledServicePrincipals;
    }

    public void setEnabledServicePrincipals(Map<String, Long> enabledServicePrincipals) {
        this.enabledServicePrincipals = enabledServicePrincipals != null
                ? enabledServicePrincipals
                : new LinkedHashMap<>();
    }
}
