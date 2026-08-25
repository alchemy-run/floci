package io.github.hectorvent.floci.services.cloudwatch.metrics.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class AlarmHistoryItem {
    private String alarmName;
    private String alarmType = "MetricAlarm";
    private long timestamp;
    private String historyItemType;
    private String historySummary;
    private String historyData;

    public String getAlarmName() { return alarmName; }
    public void setAlarmName(String alarmName) { this.alarmName = alarmName; }

    public String getAlarmType() { return alarmType; }
    public void setAlarmType(String alarmType) { this.alarmType = alarmType; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    public String getHistoryItemType() { return historyItemType; }
    public void setHistoryItemType(String historyItemType) { this.historyItemType = historyItemType; }

    public String getHistorySummary() { return historySummary; }
    public void setHistorySummary(String historySummary) { this.historySummary = historySummary; }

    public String getHistoryData() { return historyData; }
    public void setHistoryData(String historyData) { this.historyData = historyData; }
}
