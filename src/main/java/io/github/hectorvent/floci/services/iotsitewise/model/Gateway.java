package io.github.hectorvent.floci.services.iotsitewise.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** An IoT SiteWise gateway. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Gateway {

    private String id;
    private String arn;
    private String name;
    private JsonNode platform;
    private String version;
    private String region;
    private long creationDate;
    private long lastUpdateDate;
    private Map<String, String> tags = new LinkedHashMap<>();

    public Gateway() {
        // default for jackson
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getArn() {
        return arn;
    }

    public void setArn(String arn) {
        this.arn = arn;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public JsonNode getPlatform() {
        return platform;
    }

    public void setPlatform(JsonNode platform) {
        this.platform = platform;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public long getCreationDate() {
        return creationDate;
    }

    public void setCreationDate(long creationDate) {
        this.creationDate = creationDate;
    }

    public long getLastUpdateDate() {
        return lastUpdateDate;
    }

    public void setLastUpdateDate(long lastUpdateDate) {
        this.lastUpdateDate = lastUpdateDate;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }
}
