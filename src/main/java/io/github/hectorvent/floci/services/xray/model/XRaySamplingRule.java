package io.github.hectorvent.floci.services.xray.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** X-Ray sampling rule, including the built-in Default fallback. */
@RegisterForReflection
public class XRaySamplingRule {
    private String ruleName;
    private String ruleArn;
    private String resourceArn = "*";
    private int priority = 10000;
    private double fixedRate = 0.05;
    private int reservoirSize = 1;
    private String serviceName = "*";
    private String serviceType = "*";
    private String host = "*";
    private String httpMethod = "*";
    private String urlPath = "*";
    private int version = 1;
    private double createdAt;
    private double modifiedAt;
    private Map<String, String> attributes = new LinkedHashMap<>();
    private Map<String, String> tags = new LinkedHashMap<>();

    public XRaySamplingRule() {
    }

    public String getRuleName() {
        return ruleName;
    }

    public void setRuleName(String ruleName) {
        this.ruleName = ruleName;
    }

    public String getRuleArn() {
        return ruleArn;
    }

    public void setRuleArn(String ruleArn) {
        this.ruleArn = ruleArn;
    }

    public String getResourceArn() {
        return resourceArn;
    }

    public void setResourceArn(String resourceArn) {
        this.resourceArn = resourceArn;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public double getFixedRate() {
        return fixedRate;
    }

    public void setFixedRate(double fixedRate) {
        this.fixedRate = fixedRate;
    }

    public int getReservoirSize() {
        return reservoirSize;
    }

    public void setReservoirSize(int reservoirSize) {
        this.reservoirSize = reservoirSize;
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public String getServiceType() {
        return serviceType;
    }

    public void setServiceType(String serviceType) {
        this.serviceType = serviceType;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public String getHttpMethod() {
        return httpMethod;
    }

    public void setHttpMethod(String httpMethod) {
        this.httpMethod = httpMethod;
    }

    public String getUrlPath() {
        return urlPath;
    }

    public void setUrlPath(String urlPath) {
        this.urlPath = urlPath;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public double getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(double createdAt) {
        this.createdAt = createdAt;
    }

    public double getModifiedAt() {
        return modifiedAt;
    }

    public void setModifiedAt(double modifiedAt) {
        this.modifiedAt = modifiedAt;
    }

    public Map<String, String> getAttributes() {
        return attributes;
    }

    public void setAttributes(Map<String, String> attributes) {
        this.attributes = attributes == null ? new LinkedHashMap<>() : new LinkedHashMap<>(attributes);
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }
}
