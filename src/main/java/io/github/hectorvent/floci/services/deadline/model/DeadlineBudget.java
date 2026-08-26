package io.github.hectorvent.floci.services.deadline.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A Deadline Cloud budget. Wire JSON is camelCase restJson1. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DeadlineBudget {

    private String farmId;
    private String budgetId;
    private String queueId;
    private String displayName;
    private String description;
    private String status = "ACTIVE";
    private double approximateDollarLimit;
    private double approximateDollarUsage;
    private String createdAt;
    private String createdBy;
    private String updatedAt;
    private String updatedBy;
    private String startTime;
    private String endTime;
    private String region;
    private String accountId;
    private List<BudgetAction> actions = new ArrayList<>();
    private Map<String, String> tags = new LinkedHashMap<>();

    public DeadlineBudget() {
    }

    public String getFarmId() {
        return farmId;
    }

    public void setFarmId(String farmId) {
        this.farmId = farmId;
    }

    public String getBudgetId() {
        return budgetId;
    }

    public void setBudgetId(String budgetId) {
        this.budgetId = budgetId;
    }

    public String getQueueId() {
        return queueId;
    }

    public void setQueueId(String queueId) {
        this.queueId = queueId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public double getApproximateDollarLimit() {
        return approximateDollarLimit;
    }

    public void setApproximateDollarLimit(double approximateDollarLimit) {
        this.approximateDollarLimit = approximateDollarLimit;
    }

    public double getApproximateDollarUsage() {
        return approximateDollarUsage;
    }

    public void setApproximateDollarUsage(double approximateDollarUsage) {
        this.approximateDollarUsage = approximateDollarUsage;
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

    public String getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(String updatedBy) {
        this.updatedBy = updatedBy;
    }

    public String getStartTime() {
        return startTime;
    }

    public void setStartTime(String startTime) {
        this.startTime = startTime;
    }

    public String getEndTime() {
        return endTime;
    }

    public void setEndTime(String endTime) {
        this.endTime = endTime;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public List<BudgetAction> getActions() {
        return actions;
    }

    public void setActions(List<BudgetAction> actions) {
        this.actions = actions == null ? new ArrayList<>() : new ArrayList<>(actions);
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }

    public String arn() {
        return "arn:aws:deadline:" + region + ":" + accountId + ":farm/" + farmId + "/budget/" + budgetId;
    }

    @RegisterForReflection
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class BudgetAction {
        private String type;
        private double thresholdPercentage;
        private String description;

        public BudgetAction() {
        }

        public BudgetAction(String type, double thresholdPercentage, String description) {
            this.type = type;
            this.thresholdPercentage = thresholdPercentage;
            this.description = description;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public double getThresholdPercentage() {
            return thresholdPercentage;
        }

        public void setThresholdPercentage(double thresholdPercentage) {
            this.thresholdPercentage = thresholdPercentage;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public String key() {
            return type + "@" + thresholdPercentage;
        }
    }
}
