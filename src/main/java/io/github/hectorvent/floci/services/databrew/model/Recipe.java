package io.github.hectorvent.floci.services.databrew.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A Glue DataBrew recipe. The working copy is LATEST_WORKING. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Recipe {

    public static final String LATEST_WORKING = "LATEST_WORKING";
    public static final String LATEST_PUBLISHED = "LATEST_PUBLISHED";

    private String name;
    private String resourceArn;
    private String description;
    private JsonNode steps;
    private long createDate;
    private long lastModifiedDate;
    private Map<String, String> tags = new LinkedHashMap<>();
    private List<RecipePublishedVersion> published = new ArrayList<>();
    /** Version this instance represents in a describe/list payload. */
    private String recipeVersion;

    public Recipe() {
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getResourceArn() {
        return resourceArn;
    }

    public void setResourceArn(String resourceArn) {
        this.resourceArn = resourceArn;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public JsonNode getSteps() {
        return steps;
    }

    public void setSteps(JsonNode steps) {
        this.steps = steps;
    }

    public long getCreateDate() {
        return createDate;
    }

    public void setCreateDate(long createDate) {
        this.createDate = createDate;
    }

    public long getLastModifiedDate() {
        return lastModifiedDate;
    }

    public void setLastModifiedDate(long lastModifiedDate) {
        this.lastModifiedDate = lastModifiedDate;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }

    public String getRecipeVersion() {
        return recipeVersion;
    }

    public void setRecipeVersion(String recipeVersion) {
        this.recipeVersion = recipeVersion;
    }

    public List<RecipePublishedVersion> getPublished() {
        return published;
    }

    public void setPublished(List<RecipePublishedVersion> published) {
        this.published = published == null ? new ArrayList<>() : new ArrayList<>(published);
    }
}
