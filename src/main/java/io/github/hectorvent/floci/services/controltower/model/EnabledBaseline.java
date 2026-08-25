package io.github.hectorvent.floci.services.controltower.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** An enabled Control Tower baseline on a target OU or account. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EnabledBaseline {

    private String arn;
    private String baselineIdentifier;
    private String baselineVersion;
    private String targetIdentifier;
    private String parentIdentifier;
    private String status;
    private String lastOperationIdentifier;
    private JsonNode parameters;
    private Map<String, String> tags = new LinkedHashMap<>();
    private String accountId;
    private String region;

    public EnabledBaseline() {
    }

    public String getArn() {
        return arn;
    }

    public void setArn(String arn) {
        this.arn = arn;
    }

    public String getBaselineIdentifier() {
        return baselineIdentifier;
    }

    public void setBaselineIdentifier(String baselineIdentifier) {
        this.baselineIdentifier = baselineIdentifier;
    }

    public String getBaselineVersion() {
        return baselineVersion;
    }

    public void setBaselineVersion(String baselineVersion) {
        this.baselineVersion = baselineVersion;
    }

    public String getTargetIdentifier() {
        return targetIdentifier;
    }

    public void setTargetIdentifier(String targetIdentifier) {
        this.targetIdentifier = targetIdentifier;
    }

    public String getParentIdentifier() {
        return parentIdentifier;
    }

    public void setParentIdentifier(String parentIdentifier) {
        this.parentIdentifier = parentIdentifier;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getLastOperationIdentifier() {
        return lastOperationIdentifier;
    }

    public void setLastOperationIdentifier(String lastOperationIdentifier) {
        this.lastOperationIdentifier = lastOperationIdentifier;
    }

    public JsonNode getParameters() {
        return parameters == null ? null : parameters.deepCopy();
    }

    public void setParameters(JsonNode parameters) {
        this.parameters = parameters == null || parameters.isNull() ? null : parameters.deepCopy();
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }
}
