package io.github.hectorvent.floci.services.s3vectors.model;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RegisterForReflection
public class VectorBucket {
    private String vectorBucketName;
    private String vectorBucketArn;
    private long creationTime = System.currentTimeMillis() / 1000;
    private Object encryptionConfiguration;
    private String policy;
    private Map<String, String> tags = new ConcurrentHashMap<>();
    private Map<String, VectorIndex> indexes = new ConcurrentHashMap<>();

    public VectorBucket() {}

    public VectorBucket(String vectorBucketName, String vectorBucketArn, Object encryptionConfiguration) {
        this.vectorBucketName = vectorBucketName;
        this.vectorBucketArn = vectorBucketArn;
        this.encryptionConfiguration = encryptionConfiguration;
    }

    public String getVectorBucketName() { return vectorBucketName; }
    public void setVectorBucketName(String vectorBucketName) { this.vectorBucketName = vectorBucketName; }

    public String getVectorBucketArn() { return vectorBucketArn; }
    public void setVectorBucketArn(String vectorBucketArn) { this.vectorBucketArn = vectorBucketArn; }

    public long getCreationTime() { return creationTime; }
    public void setCreationTime(long creationTime) { this.creationTime = creationTime; }

    public Object getEncryptionConfiguration() { return encryptionConfiguration; }
    public void setEncryptionConfiguration(Object encryptionConfiguration) { this.encryptionConfiguration = encryptionConfiguration; }

    public String getPolicy() { return policy; }
    public void setPolicy(String policy) { this.policy = policy; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags != null ? tags : new ConcurrentHashMap<>(); }

    public Map<String, VectorIndex> getIndexes() { return indexes; }
    public void setIndexes(Map<String, VectorIndex> indexes) { this.indexes = indexes; }
}
