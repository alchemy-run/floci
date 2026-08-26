package io.github.hectorvent.floci.services.s3.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * S3 Control access point attached to a general-purpose bucket.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class S3AccessPoint {

    private String name;
    private String bucket;
    private String bucketAccountId;
    private String accountId;
    private String region;
    private String arn;
    private String alias;
    private String networkOrigin;
    private String vpcId;
    private boolean blockPublicAcls = true;
    private boolean ignorePublicAcls = true;
    private boolean blockPublicPolicy = true;
    private boolean restrictPublicBuckets = true;
    private Instant creationDate;
    private String policy;
    private Map<String, String> tags = new LinkedHashMap<>();

    public S3AccessPoint() {}

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getBucket() { return bucket; }
    public void setBucket(String bucket) { this.bucket = bucket; }

    public String getBucketAccountId() { return bucketAccountId; }
    public void setBucketAccountId(String bucketAccountId) { this.bucketAccountId = bucketAccountId; }

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public String getAlias() { return alias; }
    public void setAlias(String alias) { this.alias = alias; }

    public String getNetworkOrigin() { return networkOrigin; }
    public void setNetworkOrigin(String networkOrigin) { this.networkOrigin = networkOrigin; }

    public String getVpcId() { return vpcId; }
    public void setVpcId(String vpcId) { this.vpcId = vpcId; }

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

    public Instant getCreationDate() { return creationDate; }
    public void setCreationDate(Instant creationDate) { this.creationDate = creationDate; }

    public String getPolicy() { return policy; }
    public void setPolicy(String policy) { this.policy = policy; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? tags : new LinkedHashMap<>();
    }
}
