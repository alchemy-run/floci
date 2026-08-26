package io.github.hectorvent.floci.services.imagebuilder.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One Image Builder resource (component, recipe, configuration, pipeline, or image).
 * Wire names are camelCase restJson1.
 */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ImageBuilderResource {

    private String arn;
    private String kind;
    private String name;
    private String version;
    private String dateCreated;
    private String dateUpdated;
    private Map<String, String> tags = new LinkedHashMap<>();
    private ObjectNode details;

    public ImageBuilderResource() {
    }

    public String getArn() {
        return arn;
    }

    public void setArn(String arn) {
        this.arn = arn;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getDateCreated() {
        return dateCreated;
    }

    public void setDateCreated(String dateCreated) {
        this.dateCreated = dateCreated;
    }

    public String getDateUpdated() {
        return dateUpdated;
    }

    public void setDateUpdated(String dateUpdated) {
        this.dateUpdated = dateUpdated;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }

    public ObjectNode getDetails() {
        return details;
    }

    public void setDetails(ObjectNode details) {
        this.details = details;
    }
}
