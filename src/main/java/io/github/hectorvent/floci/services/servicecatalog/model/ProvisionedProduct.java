package io.github.hectorvent.floci.services.servicecatalog.model;

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
public class ProvisionedProduct {

    private String id;
    private String name;
    private String arn;
    private String type = "CFN_STACK";
    private String status;
    private String region;
    private String productId;
    private String provisioningArtifactId;
    private String pathId;
    private String lastRecordId;
    private String lastProvisioningRecordId;
    private String lastSuccessfulProvisioningRecordId;
    private String provisionToken;
    private String stackArn;
    private long createdTime;
    private Map<String, String> tags = new LinkedHashMap<>();
    private List<Output> outputs = new ArrayList<>();

    public ProvisionedProduct() {
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

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getProductId() {
        return productId;
    }

    public void setProductId(String productId) {
        this.productId = productId;
    }

    public String getProvisioningArtifactId() {
        return provisioningArtifactId;
    }

    public void setProvisioningArtifactId(String provisioningArtifactId) {
        this.provisioningArtifactId = provisioningArtifactId;
    }

    public String getPathId() {
        return pathId;
    }

    public void setPathId(String pathId) {
        this.pathId = pathId;
    }

    public String getLastRecordId() {
        return lastRecordId;
    }

    public void setLastRecordId(String lastRecordId) {
        this.lastRecordId = lastRecordId;
    }

    public String getLastProvisioningRecordId() {
        return lastProvisioningRecordId;
    }

    public void setLastProvisioningRecordId(String lastProvisioningRecordId) {
        this.lastProvisioningRecordId = lastProvisioningRecordId;
    }

    public String getLastSuccessfulProvisioningRecordId() {
        return lastSuccessfulProvisioningRecordId;
    }

    public void setLastSuccessfulProvisioningRecordId(String lastSuccessfulProvisioningRecordId) {
        this.lastSuccessfulProvisioningRecordId = lastSuccessfulProvisioningRecordId;
    }

    public String getProvisionToken() {
        return provisionToken;
    }

    public void setProvisionToken(String provisionToken) {
        this.provisionToken = provisionToken;
    }

    public String getStackArn() {
        return stackArn;
    }

    public void setStackArn(String stackArn) {
        this.stackArn = stackArn;
    }

    public long getCreatedTime() {
        return createdTime;
    }

    public void setCreatedTime(long createdTime) {
        this.createdTime = createdTime;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? tags : new LinkedHashMap<>();
    }

    public List<Output> getOutputs() {
        return outputs;
    }

    public void setOutputs(List<Output> outputs) {
        this.outputs = outputs != null ? outputs : new ArrayList<>();
    }

    @RegisterForReflection
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Output {
        private String key;
        private String value;
        private String description;

        public Output() {
        }

        public Output(String key, String value, String description) {
            this.key = key;
            this.value = value;
            this.description = description;
        }

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }
    }
}
