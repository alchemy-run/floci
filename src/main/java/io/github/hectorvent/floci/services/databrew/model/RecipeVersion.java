package io.github.hectorvent.floci.services.databrew.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** A published DataBrew recipe snapshot. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RecipeVersion {

    private String recipeVersion;
    private String description;
    private JsonNode steps;
    private long publishedDate;
    private String publishedBy;

    public RecipeVersion() {
    }

    public String getRecipeVersion() {
        return recipeVersion;
    }

    public void setRecipeVersion(String recipeVersion) {
        this.recipeVersion = recipeVersion;
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
        this.steps = steps == null ? null : steps.deepCopy();
    }

    public long getPublishedDate() {
        return publishedDate;
    }

    public void setPublishedDate(long publishedDate) {
        this.publishedDate = publishedDate;
    }

    public String getPublishedBy() {
        return publishedBy;
    }

    public void setPublishedBy(String publishedBy) {
        this.publishedBy = publishedBy;
    }
}
