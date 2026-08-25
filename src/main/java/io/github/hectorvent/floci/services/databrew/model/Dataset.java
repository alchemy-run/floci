package io.github.hectorvent.floci.services.databrew.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** A Glue DataBrew dataset. Wire names are PascalCase restJson1. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Dataset {

    private String name;
    private String resourceArn;
    private String format;
    private JsonNode formatOptions;
    private JsonNode input;
    private JsonNode pathOptions;
    private String source;
    private long createDate;
    private long lastModifiedDate;
    private Map<String, String> tags = new LinkedHashMap<>();

    public Dataset() {
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

    public String getFormat() {
        return format;
    }

    public void setFormat(String format) {
        this.format = format;
    }

    public JsonNode getFormatOptions() {
        return formatOptions;
    }

    public void setFormatOptions(JsonNode formatOptions) {
        this.formatOptions = formatOptions;
    }

    public JsonNode getInput() {
        return input;
    }

    public void setInput(JsonNode input) {
        this.input = input;
    }

    public JsonNode getPathOptions() {
        return pathOptions;
    }

    public void setPathOptions(JsonNode pathOptions) {
        this.pathOptions = pathOptions;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
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
}
