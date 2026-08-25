package io.github.hectorvent.floci.services.bedrockdataautomation.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** An Amazon Bedrock Data Automation blueprint. Wire names are camelCase. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Blueprint {

    private String blueprintArn;
    private String blueprintName;
    private String schema;
    private String type;
    private String blueprintStage;
    private String blueprintVersion;
    private String creationTime;
    private String lastModifiedTime;
    private String kmsKeyId;
    private Map<String, String> kmsEncryptionContext;
    private Map<String, String> tags = new LinkedHashMap<>();
    private String clientToken;
    private String region;

    public Blueprint() {
    }

    public String getBlueprintArn() {
        return blueprintArn;
    }

    public void setBlueprintArn(String blueprintArn) {
        this.blueprintArn = blueprintArn;
    }

    public String getBlueprintName() {
        return blueprintName;
    }

    public void setBlueprintName(String blueprintName) {
        this.blueprintName = blueprintName;
    }

    public String getSchema() {
        return schema;
    }

    public void setSchema(String schema) {
        this.schema = schema;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getBlueprintStage() {
        return blueprintStage;
    }

    public void setBlueprintStage(String blueprintStage) {
        this.blueprintStage = blueprintStage;
    }

    public String getBlueprintVersion() {
        return blueprintVersion;
    }

    public void setBlueprintVersion(String blueprintVersion) {
        this.blueprintVersion = blueprintVersion;
    }

    public String getCreationTime() {
        return creationTime;
    }

    public void setCreationTime(String creationTime) {
        this.creationTime = creationTime;
    }

    public String getLastModifiedTime() {
        return lastModifiedTime;
    }

    public void setLastModifiedTime(String lastModifiedTime) {
        this.lastModifiedTime = lastModifiedTime;
    }

    public String getKmsKeyId() {
        return kmsKeyId;
    }

    public void setKmsKeyId(String kmsKeyId) {
        this.kmsKeyId = kmsKeyId;
    }

    public Map<String, String> getKmsEncryptionContext() {
        return kmsEncryptionContext;
    }

    public void setKmsEncryptionContext(Map<String, String> kmsEncryptionContext) {
        this.kmsEncryptionContext = kmsEncryptionContext;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : tags;
    }

    public String getClientToken() {
        return clientToken;
    }

    public void setClientToken(String clientToken) {
        this.clientToken = clientToken;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }
}
