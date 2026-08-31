package io.github.hectorvent.floci.services.databrew.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** A published snapshot of a DataBrew recipe. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RecipePublishedVersion {

    private String recipeVersion;
    private String description;
    private JsonNode steps;
    private long publishedDate;

    public RecipePublishedVersion() {
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
        this.steps = steps;
    }

    public long getPublishedDate() {
        return publishedDate;
    }

    public void setPublishedDate(long publishedDate) {
        this.publishedDate = publishedDate;
    }
}
