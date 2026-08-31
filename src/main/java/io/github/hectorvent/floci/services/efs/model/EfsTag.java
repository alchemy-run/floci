package io.github.hectorvent.floci.services.efs.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** An EFS tag. Wire names are PascalCase restJson1. */
@RegisterForReflection
public class EfsTag {

    private String key;
    private String value;

    public EfsTag() {
    }

    public EfsTag(String key, String value) {
        this.key = key;
        this.value = value;
    }

    @JsonProperty("Key")
    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    @JsonProperty("Value")
    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }
}
