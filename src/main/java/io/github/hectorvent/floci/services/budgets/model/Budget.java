package io.github.hectorvent.floci.services.budgets.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persisted AWS Budgets {@code Budget} record, including notifications,
 * subscribers, and resource tags that live alongside it.
 *
 * @see <a href="https://docs.aws.amazon.com/aws-cost-management/latest/APIReference/API_budgets_Budget.html">Budget</a>
 */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class Budget {

    private String budgetName;
    private String budgetType;
    private String timeUnit;
    private String limitAmount;
    private String limitUnit;
    private Spend budgetLimit;
    private String billingViewArn;
    private Map<String, List<String>> costFilters;
    private long lastUpdatedTime;
    private Map<String, String> tags = new LinkedHashMap<>();
    private List<Notification> notifications = new ArrayList<>();

    public Budget() {
    }

    public String getBudgetName() {
        return budgetName;
    }

    public void setBudgetName(String budgetName) {
        this.budgetName = budgetName;
    }

    public String getBudgetType() {
        return budgetType;
    }

    public void setBudgetType(String budgetType) {
        this.budgetType = budgetType;
    }

    public String getTimeUnit() {
        return timeUnit;
    }

    public void setTimeUnit(String timeUnit) {
        this.timeUnit = timeUnit;
    }

    public String getLimitAmount() {
        return limitAmount;
    }

    public void setLimitAmount(String limitAmount) {
        this.limitAmount = limitAmount;
    }

    public String getLimitUnit() {
        return limitUnit;
    }

    public void setLimitUnit(String limitUnit) {
        this.limitUnit = limitUnit;
    }

    public Map<String, List<String>> getCostFilters() {
        return costFilters;
    }

    public void setCostFilters(Map<String, List<String>> costFilters) {
        this.costFilters = costFilters;
    }

    public long getLastUpdatedTime() {
        return lastUpdatedTime;
    }

    public void setLastUpdatedTime(long lastUpdatedTime) {
        this.lastUpdatedTime = lastUpdatedTime;
    }

    public Map<String, String> getTags() {
        if (tags == null) {
            tags = new LinkedHashMap<>();
        }
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : tags;
    }

    public List<Notification> getNotifications() {
        if (notifications == null) {
            notifications = new ArrayList<>();
        }
        return notifications;
    }

    public void setNotifications(List<Notification> notifications) {
        this.notifications = notifications == null ? new ArrayList<>() : notifications;
    }

    public Spend getBudgetLimit() {
        if (budgetLimit != null) {
            return budgetLimit;
        }
        if (limitAmount == null && limitUnit == null) {
            return null;
        }
        return new Spend(limitAmount, limitUnit);
    }

    public void setBudgetLimit(Spend budgetLimit) {
        this.budgetLimit = budgetLimit;
        if (budgetLimit != null) {
            this.limitAmount = budgetLimit.getAmount();
            this.limitUnit = budgetLimit.getUnit();
        }
    }

    public String getBillingViewArn() {
        return billingViewArn;
    }

    public void setBillingViewArn(String billingViewArn) {
        this.billingViewArn = billingViewArn;
    }
}
