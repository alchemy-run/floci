package io.github.hectorvent.floci.services.budgets.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BudgetAction {

    private String actionId;
    private String budgetName;
    private String notificationType;
    private String actionType;
    private double actionThresholdValue;
    private String actionThresholdType;
    private String iamPolicyArn;
    private List<String> iamRoles;
    private List<String> iamGroups;
    private List<String> iamUsers;
    private String scpPolicyId;
    private List<String> scpTargetIds;
    private String ssmActionSubType;
    private String ssmRegion;
    private List<String> ssmInstanceIds;
    private String executionRoleArn;
    private String approvalModel;
    private String status;
    private List<Subscriber> subscribers = new ArrayList<>();
    private Map<String, String> tags = new HashMap<>();
    private List<ActionHistory> histories = new ArrayList<>();

    public BudgetAction() {
    }

    public String getActionId() {
        return actionId;
    }

    public void setActionId(String actionId) {
        this.actionId = actionId;
    }

    public String getBudgetName() {
        return budgetName;
    }

    public void setBudgetName(String budgetName) {
        this.budgetName = budgetName;
    }

    public String getNotificationType() {
        return notificationType;
    }

    public void setNotificationType(String notificationType) {
        this.notificationType = notificationType;
    }

    public String getActionType() {
        return actionType;
    }

    public void setActionType(String actionType) {
        this.actionType = actionType;
    }

    public double getActionThresholdValue() {
        return actionThresholdValue;
    }

    public void setActionThresholdValue(double actionThresholdValue) {
        this.actionThresholdValue = actionThresholdValue;
    }

    public String getActionThresholdType() {
        return actionThresholdType;
    }

    public void setActionThresholdType(String actionThresholdType) {
        this.actionThresholdType = actionThresholdType;
    }

    public String getIamPolicyArn() {
        return iamPolicyArn;
    }

    public void setIamPolicyArn(String iamPolicyArn) {
        this.iamPolicyArn = iamPolicyArn;
    }

    public List<String> getIamRoles() {
        return iamRoles;
    }

    public void setIamRoles(List<String> iamRoles) {
        this.iamRoles = iamRoles;
    }

    public List<String> getIamGroups() {
        return iamGroups;
    }

    public void setIamGroups(List<String> iamGroups) {
        this.iamGroups = iamGroups;
    }

    public List<String> getIamUsers() {
        return iamUsers;
    }

    public void setIamUsers(List<String> iamUsers) {
        this.iamUsers = iamUsers;
    }

    public String getScpPolicyId() {
        return scpPolicyId;
    }

    public void setScpPolicyId(String scpPolicyId) {
        this.scpPolicyId = scpPolicyId;
    }

    public List<String> getScpTargetIds() {
        return scpTargetIds;
    }

    public void setScpTargetIds(List<String> scpTargetIds) {
        this.scpTargetIds = scpTargetIds;
    }

    public String getSsmActionSubType() {
        return ssmActionSubType;
    }

    public void setSsmActionSubType(String ssmActionSubType) {
        this.ssmActionSubType = ssmActionSubType;
    }

    public String getSsmRegion() {
        return ssmRegion;
    }

    public void setSsmRegion(String ssmRegion) {
        this.ssmRegion = ssmRegion;
    }

    public List<String> getSsmInstanceIds() {
        return ssmInstanceIds;
    }

    public void setSsmInstanceIds(List<String> ssmInstanceIds) {
        this.ssmInstanceIds = ssmInstanceIds;
    }

    public String getExecutionRoleArn() {
        return executionRoleArn;
    }

    public void setExecutionRoleArn(String executionRoleArn) {
        this.executionRoleArn = executionRoleArn;
    }

    public String getApprovalModel() {
        return approvalModel;
    }

    public void setApprovalModel(String approvalModel) {
        this.approvalModel = approvalModel;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public List<Subscriber> getSubscribers() {
        return subscribers;
    }

    public void setSubscribers(List<Subscriber> subscribers) {
        this.subscribers = subscribers == null ? new ArrayList<>() : subscribers;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new HashMap<>() : tags;
    }

    public List<ActionHistory> getHistories() {
        return histories;
    }

    public void setHistories(List<ActionHistory> histories) {
        this.histories = histories == null ? new ArrayList<>() : histories;
    }

    public BudgetAction snapshot() {
        BudgetAction copy = new BudgetAction();
        copy.actionId = actionId;
        copy.budgetName = budgetName;
        copy.notificationType = notificationType;
        copy.actionType = actionType;
        copy.actionThresholdValue = actionThresholdValue;
        copy.actionThresholdType = actionThresholdType;
        copy.iamPolicyArn = iamPolicyArn;
        copy.iamRoles = iamRoles == null ? null : new ArrayList<>(iamRoles);
        copy.iamGroups = iamGroups == null ? null : new ArrayList<>(iamGroups);
        copy.iamUsers = iamUsers == null ? null : new ArrayList<>(iamUsers);
        copy.scpPolicyId = scpPolicyId;
        copy.scpTargetIds = scpTargetIds == null ? null : new ArrayList<>(scpTargetIds);
        copy.ssmActionSubType = ssmActionSubType;
        copy.ssmRegion = ssmRegion;
        copy.ssmInstanceIds = ssmInstanceIds == null ? null : new ArrayList<>(ssmInstanceIds);
        copy.executionRoleArn = executionRoleArn;
        copy.approvalModel = approvalModel;
        copy.status = status;
        copy.subscribers = new ArrayList<>();
        for (Subscriber subscriber : subscribers) {
            copy.subscribers.add(subscriber.copy());
        }
        copy.tags = new HashMap<>(tags);
        return copy;
    }
}
