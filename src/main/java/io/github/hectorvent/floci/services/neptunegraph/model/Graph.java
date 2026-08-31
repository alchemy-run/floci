package io.github.hectorvent.floci.services.neptunegraph.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** A Neptune Analytics graph. Wire names are camelCase restJson1. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Graph {

    private String id;
    private String name;
    private String arn;
    private String status;
    private long createTime;
    private int provisionedMemory;
    private String endpoint;
    private boolean publicConnectivity;
    private int replicaCount;
    private String kmsKeyIdentifier;
    private Integer vectorSearchDimension;
    private boolean deletionProtection;
    private String buildNumber;
    private String region;
    private Map<String, String> tags;

    public Graph() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getArn() {
        return arn;
    }

    public void setArn(String arn) {
        this.arn = arn;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public long getCreateTime() {
        return createTime;
    }

    public void setCreateTime(long createTime) {
        this.createTime = createTime;
    }

    public int getProvisionedMemory() {
        return provisionedMemory;
    }

    public void setProvisionedMemory(int provisionedMemory) {
        this.provisionedMemory = provisionedMemory;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public boolean isPublicConnectivity() {
        return publicConnectivity;
    }

    public void setPublicConnectivity(boolean publicConnectivity) {
        this.publicConnectivity = publicConnectivity;
    }

    public int getReplicaCount() {
        return replicaCount;
    }

    public void setReplicaCount(int replicaCount) {
        this.replicaCount = replicaCount;
    }

    public String getKmsKeyIdentifier() {
        return kmsKeyIdentifier;
    }

    public void setKmsKeyIdentifier(String kmsKeyIdentifier) {
        this.kmsKeyIdentifier = kmsKeyIdentifier;
    }

    public Integer getVectorSearchDimension() {
        return vectorSearchDimension;
    }

    public void setVectorSearchDimension(Integer vectorSearchDimension) {
        this.vectorSearchDimension = vectorSearchDimension;
    }

    public boolean isDeletionProtection() {
        return deletionProtection;
    }

    public void setDeletionProtection(boolean deletionProtection) {
        this.deletionProtection = deletionProtection;
    }

    public String getBuildNumber() {
        return buildNumber;
    }

    public void setBuildNumber(String buildNumber) {
        this.buildNumber = buildNumber;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? null : new LinkedHashMap<>(tags);
    }
}
