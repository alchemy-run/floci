package io.github.hectorvent.floci.services.s3.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * S3 Object Lambda Access Point — intercepts GetObject/HeadObject/ListObjects
 * through a supporting access point and transforms the response with Lambda.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class S3ObjectLambdaAccessPoint {

    private String name;
    private String accountId;
    private String region;
    private String arn;
    private String alias;
    private String aliasStatus = "READY";
    private String supportingAccessPoint;
    private boolean cloudWatchMetricsEnabled;
    private List<String> allowedFeatures = new ArrayList<>();
    private List<Transformation> transformationConfigurations = new ArrayList<>();
    private Instant creationDate;
    private boolean blockPublicAcls = true;
    private boolean ignorePublicAcls = true;
    private boolean blockPublicPolicy = true;
    private boolean restrictPublicBuckets = true;

    public S3ObjectLambdaAccessPoint() {}

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public String getAlias() { return alias; }
    public void setAlias(String alias) { this.alias = alias; }

    public String getAliasStatus() { return aliasStatus; }
    public void setAliasStatus(String aliasStatus) { this.aliasStatus = aliasStatus; }

    public String getSupportingAccessPoint() { return supportingAccessPoint; }
    public void setSupportingAccessPoint(String supportingAccessPoint) {
        this.supportingAccessPoint = supportingAccessPoint;
    }

    public boolean isCloudWatchMetricsEnabled() { return cloudWatchMetricsEnabled; }
    public void setCloudWatchMetricsEnabled(boolean cloudWatchMetricsEnabled) {
        this.cloudWatchMetricsEnabled = cloudWatchMetricsEnabled;
    }

    public List<String> getAllowedFeatures() { return allowedFeatures; }
    public void setAllowedFeatures(List<String> allowedFeatures) {
        this.allowedFeatures = allowedFeatures != null ? allowedFeatures : new ArrayList<>();
    }

    public List<Transformation> getTransformationConfigurations() { return transformationConfigurations; }
    public void setTransformationConfigurations(List<Transformation> transformationConfigurations) {
        this.transformationConfigurations = transformationConfigurations != null
                ? transformationConfigurations : new ArrayList<>();
    }

    public Instant getCreationDate() { return creationDate; }
    public void setCreationDate(Instant creationDate) { this.creationDate = creationDate; }

    public boolean isBlockPublicAcls() { return blockPublicAcls; }
    public void setBlockPublicAcls(boolean blockPublicAcls) { this.blockPublicAcls = blockPublicAcls; }

    public boolean isIgnorePublicAcls() { return ignorePublicAcls; }
    public void setIgnorePublicAcls(boolean ignorePublicAcls) { this.ignorePublicAcls = ignorePublicAcls; }

    public boolean isBlockPublicPolicy() { return blockPublicPolicy; }
    public void setBlockPublicPolicy(boolean blockPublicPolicy) { this.blockPublicPolicy = blockPublicPolicy; }

    public boolean isRestrictPublicBuckets() { return restrictPublicBuckets; }
    public void setRestrictPublicBuckets(boolean restrictPublicBuckets) {
        this.restrictPublicBuckets = restrictPublicBuckets;
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Transformation {
        private List<String> actions = new ArrayList<>();
        private String functionArn;
        private String functionPayload;

        public Transformation() {}

        public List<String> getActions() { return actions; }
        public void setActions(List<String> actions) {
            this.actions = actions != null ? actions : new ArrayList<>();
        }

        public String getFunctionArn() { return functionArn; }
        public void setFunctionArn(String functionArn) { this.functionArn = functionArn; }

        public String getFunctionPayload() { return functionPayload; }
        public void setFunctionPayload(String functionPayload) { this.functionPayload = functionPayload; }
    }
}
