package io.github.hectorvent.floci.services.ssmincidents.model;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** Related item attached to an incident record. */
@RegisterForReflection
public class RelatedItem {

    private String title;
    private String generatedId;
    private String type;
    private JsonNode value;

    public RelatedItem() {
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getGeneratedId() {
        return generatedId;
    }

    public void setGeneratedId(String generatedId) {
        this.generatedId = generatedId;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public JsonNode getValue() {
        return value == null ? null : value.deepCopy();
    }

    public void setValue(JsonNode value) {
        this.value = value == null || value.isNull() ? null : value.deepCopy();
    }
}
