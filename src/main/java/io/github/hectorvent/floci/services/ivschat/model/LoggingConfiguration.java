package io.github.hectorvent.floci.services.ivschat.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An Amazon IVS Chat logging configuration. Wire names are camelCase.
 */
@RegisterForReflection
public class LoggingConfiguration {

    private String id;
    private String arn;
    private String name;
    private String state;
    private String s3BucketName;
    private String cloudWatchLogsLogGroupName;
    private String firehoseDeliveryStreamName;
    private String createTime;
    private String updateTime;
    private Map<String, String> tags = new LinkedHashMap<>();

    public LoggingConfiguration() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getArn() {
        return arn;
    }

    public void setArn(String arn) {
        this.arn = arn;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getS3BucketName() {
        return s3BucketName;
    }

    public void setS3BucketName(String s3BucketName) {
        this.s3BucketName = s3BucketName;
    }

    public String getCloudWatchLogsLogGroupName() {
        return cloudWatchLogsLogGroupName;
    }

    public void setCloudWatchLogsLogGroupName(String cloudWatchLogsLogGroupName) {
        this.cloudWatchLogsLogGroupName = cloudWatchLogsLogGroupName;
    }

    public String getFirehoseDeliveryStreamName() {
        return firehoseDeliveryStreamName;
    }

    public void setFirehoseDeliveryStreamName(String firehoseDeliveryStreamName) {
        this.firehoseDeliveryStreamName = firehoseDeliveryStreamName;
    }

    public String getCreateTime() {
        return createTime;
    }

    public void setCreateTime(String createTime) {
        this.createTime = createTime;
    }

    public String getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(String updateTime) {
        this.updateTime = updateTime;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }
}
