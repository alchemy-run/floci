package io.github.hectorvent.floci.services.iotsitewise.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** An IoT SiteWise asset model. Wire names are camelCase restJson1. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AssetModel {


    private String id;
    private String arn;
    private String name;
    private String type;
    private String description;
    private JsonNode properties;
    private JsonNode hierarchies;
    private JsonNode compositeModels;
    private String region;
    private long creationDate;
    private long lastUpdateDate;
    private Map<String, String> tags = new LinkedHashMap<>();

    public AssetModel() {
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

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public JsonNode getProperties() {
        return properties;
    }

    public void setProperties(JsonNode properties) {
        this.properties = properties;
    }

    public JsonNode getHierarchies() {
        return hierarchies;
    }

    public void setHierarchies(JsonNode hierarchies) {
        this.hierarchies = hierarchies;
    }

    public JsonNode getCompositeModels() {
        return compositeModels;
    }

    public void setCompositeModels(JsonNode compositeModels) {
        this.compositeModels = compositeModels;
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
