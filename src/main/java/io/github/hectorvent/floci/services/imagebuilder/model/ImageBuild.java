package io.github.hectorvent.floci.services.imagebuilder.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** An EC2 Image Builder image build version created by a pipeline execution. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class ImageBuild {

    private String arn;
    private String name;
    private String version;
    private int buildVersion = 1;
    private String type = "AMI";
    private String platform;
    private String status = "BUILDING";
    private String reason;
    private String owner;
    private String sourcePipelineName;
    private String sourcePipelineArn;
    private String imageRecipeArn;
    private String infrastructureConfigurationArn;
    private String dateCreated;
    private String buildType = "USER_INITIATED";
    private String imageSource = "CUSTOM";
    private String clientToken;
    private String workflowExecutionId;
    private Map<String, String> tags = new LinkedHashMap<>();
    private List<String> packages = new ArrayList<>();

    public ImageBuild() {
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

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public int getBuildVersion() {
        return buildVersion;
    }

    public void setBuildVersion(int buildVersion) {
        this.buildVersion = buildVersion;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getPlatform() {
        return platform;
    }

    public void setPlatform(String platform) {
        this.platform = platform;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public String getSourcePipelineName() {
        return sourcePipelineName;
    }

    public void setSourcePipelineName(String sourcePipelineName) {
        this.sourcePipelineName = sourcePipelineName;
    }

    public String getSourcePipelineArn() {
        return sourcePipelineArn;
    }

    public void setSourcePipelineArn(String sourcePipelineArn) {
        this.sourcePipelineArn = sourcePipelineArn;
    }

    public String getImageRecipeArn() {
        return imageRecipeArn;
    }

    public void setImageRecipeArn(String imageRecipeArn) {
        this.imageRecipeArn = imageRecipeArn;
    }

    public String getInfrastructureConfigurationArn() {
        return infrastructureConfigurationArn;
    }

    public void setInfrastructureConfigurationArn(String infrastructureConfigurationArn) {
        this.infrastructureConfigurationArn = infrastructureConfigurationArn;
    }

    public String getDateCreated() {
        return dateCreated;
    }

    public void setDateCreated(String dateCreated) {
        this.dateCreated = dateCreated;
    }

    public String getBuildType() {
        return buildType;
    }

    public void setBuildType(String buildType) {
        this.buildType = buildType;
    }

    public String getImageSource() {
        return imageSource;
    }

    public void setImageSource(String imageSource) {
        this.imageSource = imageSource;
    }

    public String getClientToken() {
        return clientToken;
    }

    public void setClientToken(String clientToken) {
        this.clientToken = clientToken;
    }

    public String getWorkflowExecutionId() {
        return workflowExecutionId;
    }

    public void setWorkflowExecutionId(String workflowExecutionId) {
        this.workflowExecutionId = workflowExecutionId;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : tags;
    }

    public List<String> getPackages() {
        return packages;
    }

    public void setPackages(List<String> packages) {
        this.packages = packages == null ? new ArrayList<>() : packages;
    }

    public String versionArn() {
        if (arn == null) {
            return null;
        }
        int slash = arn.lastIndexOf('/');
        return slash < 0 ? arn : arn.substring(0, slash);
    }
}
