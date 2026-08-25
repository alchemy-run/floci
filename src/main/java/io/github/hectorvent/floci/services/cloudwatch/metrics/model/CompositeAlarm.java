package io.github.hectorvent.floci.services.cloudwatch.metrics.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class CompositeAlarm {
    private String alarmName;
    private String alarmArn;
    private String alarmDescription;
    private String alarmRule;
    private long alarmConfigurationUpdatedTimestamp;
    private boolean actionsEnabled = true;
    private List<String> okActions = new ArrayList<>();
    private List<String> alarmActions = new ArrayList<>();
    private List<String> insufficientDataActions = new ArrayList<>();
    private String stateValue = "INSUFFICIENT_DATA";
    private String stateReason = "Unchecked";
    private String stateReasonData;
    private long stateUpdatedTimestamp;
    private String actionsSuppressor;
    private Integer actionsSuppressorWaitPeriod;
    private Integer actionsSuppressorExtensionPeriod;
    private Map<String, String> tags = new HashMap<>();

    public CompositeAlarm() {
        long now = Instant.now().getEpochSecond();
        this.alarmConfigurationUpdatedTimestamp = now;
        this.stateUpdatedTimestamp = now;
    }

    public String getAlarmName() { return alarmName; }
    public void setAlarmName(String alarmName) { this.alarmName = alarmName; }

    public String getAlarmArn() { return alarmArn; }
    public void setAlarmArn(String alarmArn) { this.alarmArn = alarmArn; }

    public String getAlarmDescription() { return alarmDescription; }
    public void setAlarmDescription(String alarmDescription) { this.alarmDescription = alarmDescription; }

    public String getAlarmRule() { return alarmRule; }
    public void setAlarmRule(String alarmRule) { this.alarmRule = alarmRule; }

    public long getAlarmConfigurationUpdatedTimestamp() { return alarmConfigurationUpdatedTimestamp; }
    public void setAlarmConfigurationUpdatedTimestamp(long timestamp) { this.alarmConfigurationUpdatedTimestamp = timestamp; }

    public boolean isActionsEnabled() { return actionsEnabled; }
    public void setActionsEnabled(boolean actionsEnabled) { this.actionsEnabled = actionsEnabled; }

    public List<String> getOkActions() { return okActions; }
    public void setOkActions(List<String> okActions) { this.okActions = okActions; }

    public List<String> getAlarmActions() { return alarmActions; }
    public void setAlarmActions(List<String> alarmActions) { this.alarmActions = alarmActions; }

    public List<String> getInsufficientDataActions() { return insufficientDataActions; }
    public void setInsufficientDataActions(List<String> insufficientDataActions) { this.insufficientDataActions = insufficientDataActions; }

    public String getStateValue() { return stateValue; }
    public void setStateValue(String stateValue) { this.stateValue = stateValue; }

    public String getStateReason() { return stateReason; }
    public void setStateReason(String stateReason) { this.stateReason = stateReason; }

    public String getStateReasonData() { return stateReasonData; }
    public void setStateReasonData(String stateReasonData) { this.stateReasonData = stateReasonData; }

    public long getStateUpdatedTimestamp() { return stateUpdatedTimestamp; }
    public void setStateUpdatedTimestamp(long stateUpdatedTimestamp) { this.stateUpdatedTimestamp = stateUpdatedTimestamp; }

    public String getActionsSuppressor() { return actionsSuppressor; }
    public void setActionsSuppressor(String actionsSuppressor) { this.actionsSuppressor = actionsSuppressor; }

    public Integer getActionsSuppressorWaitPeriod() { return actionsSuppressorWaitPeriod; }
    public void setActionsSuppressorWaitPeriod(Integer actionsSuppressorWaitPeriod) {
        this.actionsSuppressorWaitPeriod = actionsSuppressorWaitPeriod;
    }

    public Integer getActionsSuppressorExtensionPeriod() { return actionsSuppressorExtensionPeriod; }
    public void setActionsSuppressorExtensionPeriod(Integer actionsSuppressorExtensionPeriod) {
        this.actionsSuppressorExtensionPeriod = actionsSuppressorExtensionPeriod;
    }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }
}
