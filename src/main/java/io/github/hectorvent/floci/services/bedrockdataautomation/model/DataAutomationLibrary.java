package io.github.hectorvent.floci.services.bedrockdataautomation.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** An Amazon Bedrock Data Automation library. Wire names are camelCase restJson1. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DataAutomationLibrary {

    private String libraryArn;
    private String libraryName;
    private String libraryDescription;
    private String status = "ACTIVE";
    private String creationTime;
    private String kmsKeyId;
    private Map<String, String> kmsEncryptionContext;
    private Map<String, String> tags = new LinkedHashMap<>();
    private String region;

    public DataAutomationLibrary() {
    }

    public String getLibraryArn() {
        return libraryArn;
    }

    public void setLibraryArn(String libraryArn) {
        this.libraryArn = libraryArn;
    }

    public String getLibraryName() {
        return libraryName;
    }

    public void setLibraryName(String libraryName) {
        this.libraryName = libraryName;
    }

    public String getLibraryDescription() {
        return libraryDescription;
    }

    public void setLibraryDescription(String libraryDescription) {
        this.libraryDescription = libraryDescription;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getCreationTime() {
        return creationTime;
    }

    public void setCreationTime(String creationTime) {
        this.creationTime = creationTime;
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

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }
}
