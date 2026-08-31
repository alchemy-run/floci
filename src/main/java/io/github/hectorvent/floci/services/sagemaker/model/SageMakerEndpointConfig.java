package io.github.hectorvent.floci.services.sagemaker.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class SageMakerEndpointConfig {

    private String endpointConfigName;
    private String endpointConfigArn;
    private String region;
    private List<Map<String, Object>> productionVariants = new ArrayList<>();
    private List<Map<String, Object>> shadowProductionVariants = new ArrayList<>();
    private Map<String, Object> dataCaptureConfig;
    private String kmsKeyId;
    private Map<String, Object> asyncInferenceConfig;
    private Map<String, Object> explainerConfig;
    private String executionRoleArn;
    private Map<String, Object> vpcConfig;
    private boolean enableNetworkIsolation;
    private long creationTime;
    private Map<String, String> tags = new LinkedHashMap<>();

    public SageMakerEndpointConfig() {
    }

    public String getEndpointConfigName() {
        return endpointConfigName;
    }

    public void setEndpointConfigName(String endpointConfigName) {
        this.endpointConfigName = endpointConfigName;
    }

    public String getEndpointConfigArn() {
        return endpointConfigArn;
    }

    public void setEndpointConfigArn(String endpointConfigArn) {
        this.endpointConfigArn = endpointConfigArn;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public List<Map<String, Object>> getProductionVariants() {
        return productionVariants;
    }

    public void setProductionVariants(List<Map<String, Object>> productionVariants) {
        this.productionVariants = productionVariants != null ? productionVariants : new ArrayList<>();
    }

    public List<Map<String, Object>> getShadowProductionVariants() {
        return shadowProductionVariants;
    }

    public void setShadowProductionVariants(List<Map<String, Object>> shadowProductionVariants) {
        this.shadowProductionVariants = shadowProductionVariants != null
                ? shadowProductionVariants
                : new ArrayList<>();
    }

    public Map<String, Object> getDataCaptureConfig() {
        return dataCaptureConfig;
    }

    public void setDataCaptureConfig(Map<String, Object> dataCaptureConfig) {
        this.dataCaptureConfig = dataCaptureConfig;
    }

    public String getKmsKeyId() {
        return kmsKeyId;
    }

    public void setKmsKeyId(String kmsKeyId) {
        this.kmsKeyId = kmsKeyId;
    }

    public Map<String, Object> getAsyncInferenceConfig() {
        return asyncInferenceConfig;
    }

    public void setAsyncInferenceConfig(Map<String, Object> asyncInferenceConfig) {
        this.asyncInferenceConfig = asyncInferenceConfig;
    }

    public Map<String, Object> getExplainerConfig() {
        return explainerConfig;
    }

    public void setExplainerConfig(Map<String, Object> explainerConfig) {
        this.explainerConfig = explainerConfig;
    }

    public String getExecutionRoleArn() {
        return executionRoleArn;
    }

    public void setExecutionRoleArn(String executionRoleArn) {
        this.executionRoleArn = executionRoleArn;
    }

    public Map<String, Object> getVpcConfig() {
        return vpcConfig;
    }

    public void setVpcConfig(Map<String, Object> vpcConfig) {
        this.vpcConfig = vpcConfig;
    }

    public boolean isEnableNetworkIsolation() {
        return enableNetworkIsolation;
    }

    public void setEnableNetworkIsolation(boolean enableNetworkIsolation) {
        this.enableNetworkIsolation = enableNetworkIsolation;
    }

    public long getCreationTime() {
        return creationTime;
    }

    public void setCreationTime(long creationTime) {
        this.creationTime = creationTime;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? tags : new LinkedHashMap<>();
    }
}
