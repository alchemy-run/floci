package io.github.hectorvent.floci.services.imagebuilder.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** An EC2 Image Builder infrastructure configuration. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class InfrastructureConfiguration {

    private String arn;
    private String name;
    private String description;
    private List<String> instanceTypes = new ArrayList<>();
    private String instanceProfileName;
    private List<String> securityGroupIds = new ArrayList<>();
    private String subnetId;
    private String keyPair;
    private boolean terminateInstanceOnFailure = true;
    private String snsTopicArn;
    private String dateCreated;
    private String dateUpdated;
    private String clientToken;
    private JsonNode logging;
    private JsonNode resourceTags;
    private JsonNode instanceMetadataOptions;
    private JsonNode placement;
    private Map<String, String> tags = new LinkedHashMap<>();

    public InfrastructureConfiguration() {
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

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public List<String> getInstanceTypes() {
        return instanceTypes;
    }

    public void setInstanceTypes(List<String> instanceTypes) {
        this.instanceTypes = instanceTypes == null ? new ArrayList<>() : instanceTypes;
    }

    public String getInstanceProfileName() {
        return instanceProfileName;
    }

    public void setInstanceProfileName(String instanceProfileName) {
        this.instanceProfileName = instanceProfileName;
    }

    public List<String> getSecurityGroupIds() {
        return securityGroupIds;
    }

    public void setSecurityGroupIds(List<String> securityGroupIds) {
        this.securityGroupIds = securityGroupIds == null ? new ArrayList<>() : securityGroupIds;
    }

    public String getSubnetId() {
        return subnetId;
    }

    public void setSubnetId(String subnetId) {
        this.subnetId = subnetId;
    }

    public String getKeyPair() {
        return keyPair;
    }

    public void setKeyPair(String keyPair) {
        this.keyPair = keyPair;
    }

    public boolean isTerminateInstanceOnFailure() {
        return terminateInstanceOnFailure;
    }

    public void setTerminateInstanceOnFailure(boolean terminateInstanceOnFailure) {
        this.terminateInstanceOnFailure = terminateInstanceOnFailure;
    }

    public String getSnsTopicArn() {
        return snsTopicArn;
    }

    public void setSnsTopicArn(String snsTopicArn) {
        this.snsTopicArn = snsTopicArn;
    }

    public String getDateCreated() {
        return dateCreated;
    }

    public void setDateCreated(String dateCreated) {
        this.dateCreated = dateCreated;
    }

    public String getDateUpdated() {
        return dateUpdated;
    }

    public void setDateUpdated(String dateUpdated) {
        this.dateUpdated = dateUpdated;
    }

    public String getClientToken() {
        return clientToken;
    }

    public void setClientToken(String clientToken) {
        this.clientToken = clientToken;
    }

    public JsonNode getLogging() {
        return logging;
    }

    public void setLogging(JsonNode logging) {
        this.logging = logging;
    }

    public JsonNode getResourceTags() {
        return resourceTags;
    }

    public void setResourceTags(JsonNode resourceTags) {
        this.resourceTags = resourceTags;
    }

    public JsonNode getInstanceMetadataOptions() {
        return instanceMetadataOptions;
    }

    public void setInstanceMetadataOptions(JsonNode instanceMetadataOptions) {
        this.instanceMetadataOptions = instanceMetadataOptions;
    }

    public JsonNode getPlacement() {
        return placement;
    }

    public void setPlacement(JsonNode placement) {
        this.placement = placement;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : tags;
    }
}
