package io.github.hectorvent.floci.services.medicalimaging.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** An AWS HealthImaging data store. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Datastore {

    private String datastoreId;
    private String datastoreName;
    private String datastoreArn;
    private String datastoreStatus;
    private String kmsKeyArn;
    private String lambdaAuthorizerArn;
    private String losslessStorageFormat;
    private String region;
    private String clientToken;
    private long createdAt;
    private long updatedAt;
    private Map<String, String> tags = new LinkedHashMap<>();

    public Datastore() {
    }

    public String getDatastoreId() {
        return datastoreId;
    }

    public void setDatastoreId(String datastoreId) {
        this.datastoreId = datastoreId;
    }

    public String getDatastoreName() {
        return datastoreName;
    }

    public void setDatastoreName(String datastoreName) {
        this.datastoreName = datastoreName;
    }

    public String getDatastoreArn() {
        return datastoreArn;
    }

    public void setDatastoreArn(String datastoreArn) {
        this.datastoreArn = datastoreArn;
    }

    public String getDatastoreStatus() {
        return datastoreStatus;
    }

    public void setDatastoreStatus(String datastoreStatus) {
        this.datastoreStatus = datastoreStatus;
    }

    public String getKmsKeyArn() {
        return kmsKeyArn;
    }

    public void setKmsKeyArn(String kmsKeyArn) {
        this.kmsKeyArn = kmsKeyArn;
    }

    public String getLambdaAuthorizerArn() {
        return lambdaAuthorizerArn;
    }

    public void setLambdaAuthorizerArn(String lambdaAuthorizerArn) {
        this.lambdaAuthorizerArn = lambdaAuthorizerArn;
    }

    public String getLosslessStorageFormat() {
        return losslessStorageFormat;
    }

    public void setLosslessStorageFormat(String losslessStorageFormat) {
        this.losslessStorageFormat = losslessStorageFormat;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getClientToken() {
        return clientToken;
    }

    public void setClientToken(String clientToken) {
        this.clientToken = clientToken;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }
}
