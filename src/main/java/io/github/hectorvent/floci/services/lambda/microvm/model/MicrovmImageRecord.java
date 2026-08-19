package io.github.hectorvent.floci.services.lambda.microvm.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A customer MicroVM image (the mutable name-addressed resource) and all of
 * its versions. States mirror the distilled {@code MicrovmImageState} enum:
 * CREATING/CREATED/CREATE_FAILED/UPDATING/UPDATED/UPDATE_FAILED/DELETING/
 * DELETED/DELETE_FAILED.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class MicrovmImageRecord {

    private String region;
    private String accountId;
    private String name;
    private String imageArn;
    private String state = "CREATING";
    private String latestActiveImageVersion;
    private String latestFailedImageVersion;
    private long createdAt;
    private Long updatedAt;
    private Map<String, String> tags = new HashMap<>();
    /** version string ("1", "2", ...) → version record, in insertion order. */
    private Map<String, MicrovmImageVersionRecord> versions = new LinkedHashMap<>();
    private int nextVersionNumber = 1;

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getImageArn() { return imageArn; }
    public void setImageArn(String imageArn) { this.imageArn = imageArn; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getLatestActiveImageVersion() { return latestActiveImageVersion; }
    public void setLatestActiveImageVersion(String v) { this.latestActiveImageVersion = v; }

    public String getLatestFailedImageVersion() { return latestFailedImageVersion; }
    public void setLatestFailedImageVersion(String v) { this.latestFailedImageVersion = v; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long updatedAt) { this.updatedAt = updatedAt; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }

    public Map<String, MicrovmImageVersionRecord> getVersions() { return versions; }
    public void setVersions(Map<String, MicrovmImageVersionRecord> versions) { this.versions = versions; }

    public int getNextVersionNumber() { return nextVersionNumber; }
    public void setNextVersionNumber(int nextVersionNumber) { this.nextVersionNumber = nextVersionNumber; }
}
