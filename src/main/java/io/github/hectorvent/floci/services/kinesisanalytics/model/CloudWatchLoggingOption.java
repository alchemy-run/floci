package io.github.hectorvent.floci.services.kinesisanalytics.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * A CloudWatch Logs destination attached to a Managed Service for Apache Flink application.
 * Stored embedded on {@link FlinkApplication}, mirroring snapshots/tags rather than a
 * top-level store — the option has no independent lifecycle outside its owning application.
 *
 * <p>Wire keys are PascalCase to match {@code CloudWatchLoggingOptionDescription}.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class CloudWatchLoggingOption {

    @JsonProperty("CloudWatchLoggingOptionId")
    private String cloudWatchLoggingOptionId;

    @JsonProperty("LogStreamARN")
    private String logStreamArn;

    public CloudWatchLoggingOption() {}

    public CloudWatchLoggingOption(String cloudWatchLoggingOptionId, String logStreamArn) {
        this.cloudWatchLoggingOptionId = cloudWatchLoggingOptionId;
        this.logStreamArn = logStreamArn;
    }

    public String getCloudWatchLoggingOptionId() {
        return cloudWatchLoggingOptionId;
    }

    public void setCloudWatchLoggingOptionId(String cloudWatchLoggingOptionId) {
        this.cloudWatchLoggingOptionId = cloudWatchLoggingOptionId;
    }

    public String getLogStreamArn() {
        return logStreamArn;
    }

    public void setLogStreamArn(String logStreamArn) {
        this.logStreamArn = logStreamArn;
    }
}
