package io.github.hectorvent.floci.services.s3.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * S3 Control Multi-Region Access Point. Control-plane identity is the name;
 * requests are addressed via the generated {@code alias}.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class S3MultiRegionAccessPoint {

    private String name;
    private String alias;
    private String accountId;
    private Instant createdAt;
    private String status = "READY";
    private String requestTokenArn;
    private boolean blockPublicAcls = true;
    private boolean ignorePublicAcls = true;
    private boolean blockPublicPolicy = true;
    private boolean restrictPublicBuckets = true;
    private List<Region> regions = new ArrayList<>();

    public S3MultiRegionAccessPoint() {}

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getAlias() { return alias; }
    public void setAlias(String alias) { this.alias = alias; }

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getRequestTokenArn() { return requestTokenArn; }
    public void setRequestTokenArn(String requestTokenArn) { this.requestTokenArn = requestTokenArn; }

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

    public List<Region> getRegions() { return regions; }
    public void setRegions(List<Region> regions) {
        this.regions = regions != null ? regions : new ArrayList<>();
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Region {
        private String bucket;
        private String region;
        private String bucketAccountId;

        public Region() {}

        public String getBucket() { return bucket; }
        public void setBucket(String bucket) { this.bucket = bucket; }

        public String getRegion() { return region; }
        public void setRegion(String region) { this.region = region; }

        public String getBucketAccountId() { return bucketAccountId; }
        public void setBucketAccountId(String bucketAccountId) { this.bucketAccountId = bucketAccountId; }
    }
}
