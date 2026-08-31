package io.github.hectorvent.floci.services.datazone.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

/** An Amazon DataZone environment. Wire names are camelCase restJson1. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Environment {

    private String id;
    private String domainId;
    private String projectId;
    private String name;
    private String description;
    private String environmentProfileId;
    private String environmentBlueprintId;
    private String awsAccountId;
    private String awsAccountRegion;
    private String provider;
    private String status;
    private String createdBy;
    private String createdAt;
    private String updatedAt;
    private String region;
    private List<String> glossaryTerms = new ArrayList<>();
    private JsonNode userParameters;

    public Environment() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getDomainId() {
        return domainId;
    }

    public void setDomainId(String domainId) {
        this.domainId = domainId;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
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

    public String getEnvironmentProfileId() {
        return environmentProfileId;
    }

    public void setEnvironmentProfileId(String environmentProfileId) {
        this.environmentProfileId = environmentProfileId;
    }

    public String getEnvironmentBlueprintId() {
        return environmentBlueprintId;
    }

    public void setEnvironmentBlueprintId(String environmentBlueprintId) {
        this.environmentBlueprintId = environmentBlueprintId;
    }

    public String getAwsAccountId() {
        return awsAccountId;
    }

    public void setAwsAccountId(String awsAccountId) {
        this.awsAccountId = awsAccountId;
    }

    public String getAwsAccountRegion() {
        return awsAccountRegion;
    }

    public void setAwsAccountRegion(String awsAccountRegion) {
        this.awsAccountRegion = awsAccountRegion;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public List<String> getGlossaryTerms() {
        return glossaryTerms;
    }

    public void setGlossaryTerms(List<String> glossaryTerms) {
        this.glossaryTerms = glossaryTerms == null ? new ArrayList<>() : new ArrayList<>(glossaryTerms);
    }

    public JsonNode getUserParameters() {
        return userParameters;
    }

    public void setUserParameters(JsonNode userParameters) {
        this.userParameters = userParameters;
    }
}
