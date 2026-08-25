package io.github.hectorvent.floci.services.aiops.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A CloudWatch investigations investigation group. Wire names are camelCase. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class InvestigationGroup {

    private String id;
    private String name;
    private String arn;
    private String roleArn;
    private long createdAt;
    private long lastModifiedAt;
    private String createdBy;
    private String lastModifiedBy;
    private int retentionInDays;
    private String encryptionType;
    private String kmsKeyId;
    private List<String> tagKeyBoundaries;
    private Map<String, List<String>> chatbotNotificationChannel;
    private boolean cloudTrailEventHistoryEnabled;
    private List<CrossAccountConfiguration> crossAccountConfigurations;
    private Map<String, String> tags;
    private String policy;

    public InvestigationGroup() {
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

    public String getRoleArn() {
        return roleArn;
    }

    public void setRoleArn(String roleArn) {
        this.roleArn = roleArn;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getLastModifiedAt() {
        return lastModifiedAt;
    }

    public void setLastModifiedAt(long lastModifiedAt) {
        this.lastModifiedAt = lastModifiedAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public String getLastModifiedBy() {
        return lastModifiedBy;
    }

    public void setLastModifiedBy(String lastModifiedBy) {
        this.lastModifiedBy = lastModifiedBy;
    }

    public int getRetentionInDays() {
        return retentionInDays;
    }

    public void setRetentionInDays(int retentionInDays) {
        this.retentionInDays = retentionInDays;
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

    public List<String> getTagKeyBoundaries() {
        return tagKeyBoundaries;
    }

    public void setTagKeyBoundaries(List<String> tagKeyBoundaries) {
        this.tagKeyBoundaries = tagKeyBoundaries == null ? null : new ArrayList<>(tagKeyBoundaries);
    }

    public Map<String, List<String>> getChatbotNotificationChannel() {
        return chatbotNotificationChannel;
    }

    public void setChatbotNotificationChannel(Map<String, List<String>> chatbotNotificationChannel) {
        if (chatbotNotificationChannel == null) {
            this.chatbotNotificationChannel = null;
            return;
        }
        Map<String, List<String>> copy = new LinkedHashMap<>();
        chatbotNotificationChannel.forEach((key, value) ->
                copy.put(key, value == null ? null : List.copyOf(value)));
        this.chatbotNotificationChannel = copy;
    }

    public boolean isCloudTrailEventHistoryEnabled() {
        return cloudTrailEventHistoryEnabled;
    }

    public void setCloudTrailEventHistoryEnabled(boolean cloudTrailEventHistoryEnabled) {
        this.cloudTrailEventHistoryEnabled = cloudTrailEventHistoryEnabled;
    }

    public List<CrossAccountConfiguration> getCrossAccountConfigurations() {
        return crossAccountConfigurations;
    }

    public void setCrossAccountConfigurations(List<CrossAccountConfiguration> crossAccountConfigurations) {
        this.crossAccountConfigurations = crossAccountConfigurations == null
                ? null
                : new ArrayList<>(crossAccountConfigurations);
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? null : new LinkedHashMap<>(tags);
    }

    public String getPolicy() {
        return policy;
    }

    public void setPolicy(String policy) {
        this.policy = policy;
    }

    @RegisterForReflection
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class CrossAccountConfiguration {
        private String sourceRoleArn;

        public CrossAccountConfiguration() {
        }

        public CrossAccountConfiguration(String sourceRoleArn) {
            this.sourceRoleArn = sourceRoleArn;
        }

        public String getSourceRoleArn() {
            return sourceRoleArn;
        }

        public void setSourceRoleArn(String sourceRoleArn) {
            this.sourceRoleArn = sourceRoleArn;
        }
    }
}
