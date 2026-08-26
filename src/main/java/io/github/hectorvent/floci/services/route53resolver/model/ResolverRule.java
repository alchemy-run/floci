package io.github.hectorvent.floci.services.route53resolver.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ResolverRule {

    private String id;
    private String arn;
    private String name;
    private String creatorRequestId;
    private String domainName;
    private String status;
    private String statusMessage;
    private String ruleType;
    private String resolverEndpointId;
    private String ownerId;
    private String shareStatus;
    private String creationTime;
    private String modificationTime;
    private String delegationRecord;
    private List<TargetIp> targetIps = new ArrayList<>();
    private Map<String, String> tags = new LinkedHashMap<>();

    public ResolverRule() {
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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCreatorRequestId() {
        return creatorRequestId;
    }

    public void setCreatorRequestId(String creatorRequestId) {
        this.creatorRequestId = creatorRequestId;
    }

    public String getDomainName() {
        return domainName;
    }

    public void setDomainName(String domainName) {
        this.domainName = domainName;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getStatusMessage() {
        return statusMessage;
    }

    public void setStatusMessage(String statusMessage) {
        this.statusMessage = statusMessage;
    }

    public String getRuleType() {
        return ruleType;
    }

    public void setRuleType(String ruleType) {
        this.ruleType = ruleType;
    }

    public String getResolverEndpointId() {
        return resolverEndpointId;
    }

    public void setResolverEndpointId(String resolverEndpointId) {
        this.resolverEndpointId = resolverEndpointId;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(String ownerId) {
        this.ownerId = ownerId;
    }

    public String getShareStatus() {
        return shareStatus;
    }

    public void setShareStatus(String shareStatus) {
        this.shareStatus = shareStatus;
    }

    public String getCreationTime() {
        return creationTime;
    }

    public void setCreationTime(String creationTime) {
        this.creationTime = creationTime;
    }

    public String getModificationTime() {
        return modificationTime;
    }

    public void setModificationTime(String modificationTime) {
        this.modificationTime = modificationTime;
    }

    public String getDelegationRecord() {
        return delegationRecord;
    }

    public void setDelegationRecord(String delegationRecord) {
        this.delegationRecord = delegationRecord;
    }

    public List<TargetIp> getTargetIps() {
        return targetIps;
    }

    public void setTargetIps(List<TargetIp> targetIps) {
        this.targetIps = targetIps != null ? targetIps : new ArrayList<>();
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? tags : new LinkedHashMap<>();
    }
}
