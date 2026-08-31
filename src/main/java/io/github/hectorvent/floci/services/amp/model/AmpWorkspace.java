package io.github.hectorvent.floci.services.amp.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AmpWorkspace {

    public static final int DEFAULT_RETENTION_DAYS = 150;

    private String workspaceId;
    private String alias;
    private String arn;
    private String statusCode;
    private String prometheusEndpoint;
    private long createdAt;
    private Map<String, String> tags = new LinkedHashMap<>();
    private String kmsKeyArn;
    private String region;
    private int retentionPeriodInDays = DEFAULT_RETENTION_DAYS;
    private JsonNode limitsPerLabelSet;
    private String configurationStatusCode = "ACTIVE";

    private String logGroupArn;
    private Long loggingCreatedAt;
    private Long loggingModifiedAt;
    private JsonNode queryDestinations;
    private Long queryLoggingCreatedAt;
    private Long queryLoggingModifiedAt;
    private String policyDocument;
    private String policyStatus;
    private String revisionId;
    private Map<String, AmpAnomalyDetector> detectors = new LinkedHashMap<>();
    private Map<String, RuleGroupsNamespace> ruleGroupsNamespaces = new LinkedHashMap<>();
    private AlertManagerDefinition alertManager;

    public AmpWorkspace() {
    }

    public String getWorkspaceId() {
        return workspaceId;
    }

    public void setWorkspaceId(String workspaceId) {
        this.workspaceId = workspaceId;
    }

    public String getAlias() {
        return alias;
    }

    public void setAlias(String alias) {
        this.alias = alias;
    }

    public String getArn() {
        return arn;
    }

    public void setArn(String arn) {
        this.arn = arn;
    }

    public String getStatusCode() {
        return statusCode;
    }

    public void setStatusCode(String statusCode) {
        this.statusCode = statusCode;
    }

    public String getPrometheusEndpoint() {
        return prometheusEndpoint;
    }

    public void setPrometheusEndpoint(String prometheusEndpoint) {
        this.prometheusEndpoint = prometheusEndpoint;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }

    public String getKmsKeyArn() {
        return kmsKeyArn;
    }

    public void setKmsKeyArn(String kmsKeyArn) {
        this.kmsKeyArn = kmsKeyArn;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public int getRetentionPeriodInDays() {
        return retentionPeriodInDays;
    }

    public void setRetentionPeriodInDays(int retentionPeriodInDays) {
        this.retentionPeriodInDays = retentionPeriodInDays;
    }

    public JsonNode getLimitsPerLabelSet() {
        return limitsPerLabelSet == null ? null : limitsPerLabelSet.deepCopy();
    }

    public void setLimitsPerLabelSet(JsonNode limitsPerLabelSet) {
        this.limitsPerLabelSet = limitsPerLabelSet == null ? null : limitsPerLabelSet.deepCopy();
    }

    public String getConfigurationStatusCode() {
        return configurationStatusCode;
    }

    public void setConfigurationStatusCode(String configurationStatusCode) {
        this.configurationStatusCode = configurationStatusCode;
    }

    public String getLogGroupArn() {
        return logGroupArn;
    }

    public void setLogGroupArn(String logGroupArn) {
        this.logGroupArn = logGroupArn;
    }

    public Long getLoggingCreatedAt() {
        return loggingCreatedAt;
    }

    public void setLoggingCreatedAt(Long loggingCreatedAt) {
        this.loggingCreatedAt = loggingCreatedAt;
    }

    public Long getLoggingModifiedAt() {
        return loggingModifiedAt;
    }

    public void setLoggingModifiedAt(Long loggingModifiedAt) {
        this.loggingModifiedAt = loggingModifiedAt;
    }

    public JsonNode getQueryDestinations() {
        return queryDestinations == null ? null : queryDestinations.deepCopy();
    }

    public void setQueryDestinations(JsonNode queryDestinations) {
        this.queryDestinations = queryDestinations == null ? null : queryDestinations.deepCopy();
    }

    public Long getQueryLoggingCreatedAt() {
        return queryLoggingCreatedAt;
    }

    public void setQueryLoggingCreatedAt(Long queryLoggingCreatedAt) {
        this.queryLoggingCreatedAt = queryLoggingCreatedAt;
    }

    public Long getQueryLoggingModifiedAt() {
        return queryLoggingModifiedAt;
    }

    public void setQueryLoggingModifiedAt(Long queryLoggingModifiedAt) {
        this.queryLoggingModifiedAt = queryLoggingModifiedAt;
    }

    public String getPolicyDocument() {
        return policyDocument;
    }

    public void setPolicyDocument(String policyDocument) {
        this.policyDocument = policyDocument;
    }

    public String getPolicyStatus() {
        return policyStatus;
    }

    public void setPolicyStatus(String policyStatus) {
        this.policyStatus = policyStatus;
    }

    public String getRevisionId() {
        return revisionId;
    }

    public void setRevisionId(String revisionId) {
        this.revisionId = revisionId;
    }

    public Map<String, AmpAnomalyDetector> getDetectors() {
        if (detectors == null) {
            detectors = new LinkedHashMap<>();
        }
        return detectors;
    }

    public void setDetectors(Map<String, AmpAnomalyDetector> detectors) {
        this.detectors = detectors == null ? new LinkedHashMap<>() : new LinkedHashMap<>(detectors);
    }

    public Map<String, RuleGroupsNamespace> getRuleGroupsNamespaces() {
        if (ruleGroupsNamespaces == null) {
            ruleGroupsNamespaces = new LinkedHashMap<>();
        }
        return ruleGroupsNamespaces;
    }

    public void setRuleGroupsNamespaces(Map<String, RuleGroupsNamespace> ruleGroupsNamespaces) {
        this.ruleGroupsNamespaces = ruleGroupsNamespaces == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(ruleGroupsNamespaces);
    }

    public AlertManagerDefinition getAlertManager() {
        return alertManager;
    }

    public void setAlertManager(AlertManagerDefinition alertManager) {
        this.alertManager = alertManager;
    }
}
