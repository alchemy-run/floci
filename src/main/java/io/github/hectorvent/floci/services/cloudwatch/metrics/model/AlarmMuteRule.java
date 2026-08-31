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
public class AlarmMuteRule {
    private String name;
    private String alarmMuteRuleArn;
    private String description;
    private String scheduleExpression;
    private String scheduleDuration;
    private String scheduleTimezone;
    private List<String> alarmNames = new ArrayList<>();
    private Long startDate;
    private Long expireDate;
    private long lastUpdatedTimestamp;
    private String muteType;
    private Map<String, String> tags = new HashMap<>();

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getAlarmMuteRuleArn() { return alarmMuteRuleArn; }
    public void setAlarmMuteRuleArn(String alarmMuteRuleArn) { this.alarmMuteRuleArn = alarmMuteRuleArn; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getScheduleExpression() { return scheduleExpression; }
    public void setScheduleExpression(String scheduleExpression) { this.scheduleExpression = scheduleExpression; }

    public String getScheduleDuration() { return scheduleDuration; }
    public void setScheduleDuration(String scheduleDuration) { this.scheduleDuration = scheduleDuration; }

    public String getScheduleTimezone() { return scheduleTimezone; }
    public void setScheduleTimezone(String scheduleTimezone) { this.scheduleTimezone = scheduleTimezone; }

    public List<String> getAlarmNames() { return alarmNames; }
    public void setAlarmNames(List<String> alarmNames) {
        this.alarmNames = alarmNames != null ? alarmNames : new ArrayList<>();
    }

    public Long getStartDate() { return startDate; }
    public void setStartDate(Long startDate) { this.startDate = startDate; }

    public Long getExpireDate() { return expireDate; }
    public void setExpireDate(Long expireDate) { this.expireDate = expireDate; }

    public long getLastUpdatedTimestamp() { return lastUpdatedTimestamp; }
    public void setLastUpdatedTimestamp(long lastUpdatedTimestamp) { this.lastUpdatedTimestamp = lastUpdatedTimestamp; }

    public String getMuteType() { return muteType; }
    public void setMuteType(String muteType) { this.muteType = muteType; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? tags : new HashMap<>();
    }

    public String status() {
        return status(Instant.now().getEpochSecond());
    }

    public String status(long nowEpoch) {
        if (expireDate != null && nowEpoch >= expireDate) {
            return "EXPIRED";
        }
        if (startDate != null && nowEpoch < startDate) {
            return "SCHEDULED";
        }
        if (startDate != null) {
            return "ACTIVE";
        }
        return "SCHEDULED";
    }
}
