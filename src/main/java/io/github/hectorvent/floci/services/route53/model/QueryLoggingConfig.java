package io.github.hectorvent.floci.services.route53.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class QueryLoggingConfig {

    private String id;
    private String hostedZoneId;
    private String cloudWatchLogsLogGroupArn;

    public QueryLoggingConfig() {}

    public QueryLoggingConfig(String id, String hostedZoneId, String cloudWatchLogsLogGroupArn) {
        this.id = id;
        this.hostedZoneId = hostedZoneId;
        this.cloudWatchLogsLogGroupArn = cloudWatchLogsLogGroupArn;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getHostedZoneId() { return hostedZoneId; }
    public void setHostedZoneId(String hostedZoneId) { this.hostedZoneId = hostedZoneId; }

    public String getCloudWatchLogsLogGroupArn() { return cloudWatchLogsLogGroupArn; }
    public void setCloudWatchLogsLogGroupArn(String cloudWatchLogsLogGroupArn) {
        this.cloudWatchLogsLogGroupArn = cloudWatchLogsLogGroupArn;
    }
}
