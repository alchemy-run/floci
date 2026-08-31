package io.github.hectorvent.floci.services.cloudfront.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Map;

@RegisterForReflection
public class DefaultCacheBehavior {

    private String targetOriginId;
    private String viewerProtocolPolicy = "redirect-to-https";
    private List<String> allowedMethods;
    private List<String> cachedMethods;
    private Map<String, Object> forwardedValues;
    private String cachePolicyId;
    private String originRequestPolicyId;
    private String responseHeadersPolicyId;
    private String fieldLevelEncryptionId;
    private String realtimeLogConfigArn;
    private List<Map<String, String>> functionAssociations;
    private List<Map<String, Object>> lambdaFunctionAssociations;
    private boolean compress;
    // Boxed: `null` distinguishes "not supplied" (cache-policy mode) from a
    // legacy-mode TTL of 0, so GetDistributionConfig round-trips faithfully.
    private Long defaultTTL;
    private Long minTTL;
    private Long maxTTL;

    public DefaultCacheBehavior() {}

    public String getTargetOriginId() { return targetOriginId; }
    public void setTargetOriginId(String targetOriginId) { this.targetOriginId = targetOriginId; }

    public String getViewerProtocolPolicy() { return viewerProtocolPolicy; }
    public void setViewerProtocolPolicy(String viewerProtocolPolicy) { this.viewerProtocolPolicy = viewerProtocolPolicy; }

    public List<String> getAllowedMethods() { return allowedMethods; }
    public void setAllowedMethods(List<String> allowedMethods) { this.allowedMethods = allowedMethods; }

    public List<String> getCachedMethods() { return cachedMethods; }
    public void setCachedMethods(List<String> cachedMethods) { this.cachedMethods = cachedMethods; }

    public String getCachePolicyId() { return cachePolicyId; }
    public void setCachePolicyId(String cachePolicyId) { this.cachePolicyId = cachePolicyId; }

    public String getOriginRequestPolicyId() { return originRequestPolicyId; }
    public void setOriginRequestPolicyId(String originRequestPolicyId) { this.originRequestPolicyId = originRequestPolicyId; }

    public String getResponseHeadersPolicyId() { return responseHeadersPolicyId; }
    public void setResponseHeadersPolicyId(String responseHeadersPolicyId) { this.responseHeadersPolicyId = responseHeadersPolicyId; }

    public String getFieldLevelEncryptionId() { return fieldLevelEncryptionId; }
    public void setFieldLevelEncryptionId(String fieldLevelEncryptionId) { this.fieldLevelEncryptionId = fieldLevelEncryptionId; }

    public String getRealtimeLogConfigArn() { return realtimeLogConfigArn; }
    public void setRealtimeLogConfigArn(String realtimeLogConfigArn) { this.realtimeLogConfigArn = realtimeLogConfigArn; }

    public List<Map<String, String>> getFunctionAssociations() { return functionAssociations; }
    public void setFunctionAssociations(List<Map<String, String>> functionAssociations) { this.functionAssociations = functionAssociations; }

    public List<Map<String, Object>> getLambdaFunctionAssociations() { return lambdaFunctionAssociations; }
    public void setLambdaFunctionAssociations(List<Map<String, Object>> lambdaFunctionAssociations) { this.lambdaFunctionAssociations = lambdaFunctionAssociations; }

    public boolean isCompress() { return compress; }
    public void setCompress(boolean compress) { this.compress = compress; }

    public Map<String, Object> getForwardedValues() { return forwardedValues; }
    public void setForwardedValues(Map<String, Object> forwardedValues) { this.forwardedValues = forwardedValues; }

    public Long getDefaultTTL() { return defaultTTL; }
    public void setDefaultTTL(Long defaultTTL) { this.defaultTTL = defaultTTL; }

    public Long getMinTTL() { return minTTL; }
    public void setMinTTL(Long minTTL) { this.minTTL = minTTL; }

    public Long getMaxTTL() { return maxTTL; }
    public void setMaxTTL(Long maxTTL) { this.maxTTL = maxTTL; }
}
