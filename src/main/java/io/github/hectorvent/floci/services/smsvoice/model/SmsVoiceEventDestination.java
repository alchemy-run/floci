package io.github.hectorvent.floci.services.smsvoice.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class SmsVoiceEventDestination {

    private String eventDestinationName;
    private boolean enabled = true;
    private List<String> matchingEventTypes = new ArrayList<>();
    private String cloudWatchLogsIamRoleArn;
    private String cloudWatchLogsLogGroupArn;
    private String kinesisFirehoseIamRoleArn;
    private String kinesisFirehoseDeliveryStreamArn;
    private String snsTopicArn;

    public SmsVoiceEventDestination() {
    }

    public String getEventDestinationName() {
        return eventDestinationName;
    }

    public void setEventDestinationName(String eventDestinationName) {
        this.eventDestinationName = eventDestinationName;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getMatchingEventTypes() {
        return matchingEventTypes;
    }

    public void setMatchingEventTypes(List<String> matchingEventTypes) {
        this.matchingEventTypes = matchingEventTypes == null ? new ArrayList<>() : matchingEventTypes;
    }

    public String getCloudWatchLogsIamRoleArn() {
        return cloudWatchLogsIamRoleArn;
    }

    public void setCloudWatchLogsIamRoleArn(String cloudWatchLogsIamRoleArn) {
        this.cloudWatchLogsIamRoleArn = cloudWatchLogsIamRoleArn;
    }

    public String getCloudWatchLogsLogGroupArn() {
        return cloudWatchLogsLogGroupArn;
    }

    public void setCloudWatchLogsLogGroupArn(String cloudWatchLogsLogGroupArn) {
        this.cloudWatchLogsLogGroupArn = cloudWatchLogsLogGroupArn;
    }

    public String getKinesisFirehoseIamRoleArn() {
        return kinesisFirehoseIamRoleArn;
    }

    public void setKinesisFirehoseIamRoleArn(String kinesisFirehoseIamRoleArn) {
        this.kinesisFirehoseIamRoleArn = kinesisFirehoseIamRoleArn;
    }

    public String getKinesisFirehoseDeliveryStreamArn() {
        return kinesisFirehoseDeliveryStreamArn;
    }

    public void setKinesisFirehoseDeliveryStreamArn(String kinesisFirehoseDeliveryStreamArn) {
        this.kinesisFirehoseDeliveryStreamArn = kinesisFirehoseDeliveryStreamArn;
    }

    public String getSnsTopicArn() {
        return snsTopicArn;
    }

    public void setSnsTopicArn(String snsTopicArn) {
        this.snsTopicArn = snsTopicArn;
    }

    public void clearDestinations() {
        cloudWatchLogsIamRoleArn = null;
        cloudWatchLogsLogGroupArn = null;
        kinesisFirehoseIamRoleArn = null;
        kinesisFirehoseDeliveryStreamArn = null;
        snsTopicArn = null;
    }
}
