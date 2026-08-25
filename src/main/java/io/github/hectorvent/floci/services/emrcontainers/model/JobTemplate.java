package io.github.hectorvent.floci.services.emrcontainers.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** An EMR on EKS job template. Wire names are camelCase. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class JobTemplate {

    private String id;
    private String name;
    private String arn;
    private String createdAt;
    private String createdBy;
    private String kmsKeyArn;
    private String clientToken;
    private String region;
    private Map<String, Object> jobTemplateData;
    private Map<String, String> tags;

    public JobTemplate() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getArn() {
        return arn;
    }

    public void setArn(String arn) {
        this.arn = arn;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public String getKmsKeyArn() {
        return kmsKeyArn;
    }

    public void setKmsKeyArn(String kmsKeyArn) {
        this.kmsKeyArn = kmsKeyArn;
    }

    public String getClientToken() {
        return clientToken;
    }

    public void setClientToken(String clientToken) {
        this.clientToken = clientToken;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public Map<String, Object> getJobTemplateData() {
        return jobTemplateData;
    }

    public void setJobTemplateData(Map<String, Object> jobTemplateData) {
        this.jobTemplateData = jobTemplateData == null ? null : new LinkedHashMap<>(jobTemplateData);
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? null : new LinkedHashMap<>(tags);
    }
}
