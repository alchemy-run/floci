package io.github.hectorvent.floci.services.devopsguru.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Per-account, per-Region DevOps Guru configuration. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DevOpsGuruAccount {

    private boolean cloudFormationConfigured;
    private Set<String> stackNames = new LinkedHashSet<>();
    private Map<String, Set<String>> tagValuesByKey = new LinkedHashMap<>();
    private List<NotificationChannel> channels = new ArrayList<>();
    private String profilerStatus = "DISABLED";
    private String opsCenterStatus = "DISABLED";
    private String logsAnomalyStatus = "DISABLED";
    private String encryptionType = "AWS_OWNED_KMS_KEY";
    private String kmsKeyId;
    private Map<String, String> feedbackByInsightId = new LinkedHashMap<>();
    private String lastFeedbackInsightId;
    private CostEstimation costEstimation;

    public DevOpsGuruAccount() {
    }

    public boolean isCloudFormationConfigured() {
        return cloudFormationConfigured;
    }

    public void setCloudFormationConfigured(boolean cloudFormationConfigured) {
        this.cloudFormationConfigured = cloudFormationConfigured;
    }

    public Set<String> getStackNames() {
        return stackNames;
    }

    public void setStackNames(Set<String> stackNames) {
        this.stackNames = stackNames == null ? new LinkedHashSet<>() : new LinkedHashSet<>(stackNames);
    }

    public Map<String, Set<String>> getTagValuesByKey() {
        return tagValuesByKey;
    }

    public void setTagValuesByKey(Map<String, Set<String>> tagValuesByKey) {
        this.tagValuesByKey = tagValuesByKey == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tagValuesByKey);
    }

    public boolean hasTagCollection() {
        return !tagValuesByKey.isEmpty();
    }

    public boolean hasAnyCollection() {
        return cloudFormationConfigured || hasTagCollection();
    }

    public List<NotificationChannel> getChannels() {
        return channels;
    }

    public void setChannels(List<NotificationChannel> channels) {
        this.channels = channels == null ? new ArrayList<>() : new ArrayList<>(channels);
    }

    public String getProfilerStatus() {
        return profilerStatus;
    }

    public void setProfilerStatus(String profilerStatus) {
        this.profilerStatus = profilerStatus;
    }

    public String getOpsCenterStatus() {
        return opsCenterStatus;
    }

    public void setOpsCenterStatus(String opsCenterStatus) {
        this.opsCenterStatus = opsCenterStatus;
    }

    public String getLogsAnomalyStatus() {
        return logsAnomalyStatus;
    }

    public void setLogsAnomalyStatus(String logsAnomalyStatus) {
        this.logsAnomalyStatus = logsAnomalyStatus;
    }

    public String getEncryptionType() {
        return encryptionType;
    }

    public void setEncryptionType(String encryptionType) {
        this.encryptionType = encryptionType;
    }

    public String getKmsKeyId() {
        return kmsKeyId;
    }

    public void setKmsKeyId(String kmsKeyId) {
        this.kmsKeyId = kmsKeyId;
    }

    public Map<String, String> getFeedbackByInsightId() {
        return feedbackByInsightId;
    }

    public void setFeedbackByInsightId(Map<String, String> feedbackByInsightId) {
        this.feedbackByInsightId = feedbackByInsightId == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(feedbackByInsightId);
    }

    public String getLastFeedbackInsightId() {
        return lastFeedbackInsightId;
    }

    public void setLastFeedbackInsightId(String lastFeedbackInsightId) {
        this.lastFeedbackInsightId = lastFeedbackInsightId;
    }

    public CostEstimation getCostEstimation() {
        return costEstimation;
    }

    public void setCostEstimation(CostEstimation costEstimation) {
        this.costEstimation = costEstimation;
    }

    @RegisterForReflection
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class CostEstimation {
        private String status;
        private double totalCost;

        public CostEstimation() {
        }

        public CostEstimation(String status, double totalCost) {
            this.status = status;
            this.totalCost = totalCost;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public double getTotalCost() {
            return totalCost;
        }

        public void setTotalCost(double totalCost) {
            this.totalCost = totalCost;
        }
    }
}
