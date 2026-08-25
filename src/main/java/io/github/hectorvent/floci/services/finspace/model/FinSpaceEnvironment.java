package io.github.hectorvent.floci.services.finspace.model;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A FinSpace environment. Wire names are camelCase restJson1. */
@RegisterForReflection
public class FinSpaceEnvironment {

    private String environmentId;
    private String environmentArn;
    private String name;
    private String description;
    private String status;
    private String awsAccountId;
    private String environmentUrl;
    private String sageMakerStudioDomainUrl;
    private String kmsKeyId;
    private String dedicatedServiceAccountId;
    private String federationMode;
    private JsonNode federationParameters;
    private JsonNode superuserParameters;
    private List<String> dataBundles = new ArrayList<>();
    private Map<String, String> tags = new LinkedHashMap<>();
    private long updateTimestamp;

    public FinSpaceEnvironment() {
    }

    public String getEnvironmentId() {
        return environmentId;
    }

    public void setEnvironmentId(String environmentId) {
        this.environmentId = environmentId;
    }

    public String getEnvironmentArn() {
        return environmentArn;
    }

    public void setEnvironmentArn(String environmentArn) {
        this.environmentArn = environmentArn;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getAwsAccountId() {
        return awsAccountId;
    }

    public void setAwsAccountId(String awsAccountId) {
        this.awsAccountId = awsAccountId;
    }

    public String getEnvironmentUrl() {
        return environmentUrl;
    }

    public void setEnvironmentUrl(String environmentUrl) {
        this.environmentUrl = environmentUrl;
    }

    public String getSageMakerStudioDomainUrl() {
        return sageMakerStudioDomainUrl;
    }

    public void setSageMakerStudioDomainUrl(String sageMakerStudioDomainUrl) {
        this.sageMakerStudioDomainUrl = sageMakerStudioDomainUrl;
    }

    public String getKmsKeyId() {
        return kmsKeyId;
    }

    public void setKmsKeyId(String kmsKeyId) {
        this.kmsKeyId = kmsKeyId;
    }

    public String getDedicatedServiceAccountId() {
        return dedicatedServiceAccountId;
    }

    public void setDedicatedServiceAccountId(String dedicatedServiceAccountId) {
        this.dedicatedServiceAccountId = dedicatedServiceAccountId;
    }

    public String getFederationMode() {
        return federationMode;
    }

    public void setFederationMode(String federationMode) {
        this.federationMode = federationMode;
    }

    public JsonNode getFederationParameters() {
        return federationParameters;
    }

    public void setFederationParameters(JsonNode federationParameters) {
        this.federationParameters = federationParameters;
    }

    public JsonNode getSuperuserParameters() {
        return superuserParameters;
    }

    public void setSuperuserParameters(JsonNode superuserParameters) {
        this.superuserParameters = superuserParameters;
    }

    public List<String> getDataBundles() {
        return dataBundles;
    }

    public void setDataBundles(List<String> dataBundles) {
        this.dataBundles = dataBundles == null ? new ArrayList<>() : new ArrayList<>(dataBundles);
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }

    public long getUpdateTimestamp() {
        return updateTimestamp;
    }

    public void setUpdateTimestamp(long updateTimestamp) {
        this.updateTimestamp = updateTimestamp;
    }
}
