package io.github.hectorvent.floci.services.simpledb.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
public class SimpleDbItem {

    private String name;
    private Map<String, List<String>> attributes = new LinkedHashMap<>();

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Map<String, List<String>> getAttributes() {
        return attributes;
    }

    public void setAttributes(Map<String, List<String>> attributes) {
        this.attributes = attributes != null ? attributes : new LinkedHashMap<>();
    }

    public List<String> values(String attributeName) {
        List<String> values = attributes.get(attributeName);
        return values == null ? List.of() : values;
    }

    public boolean isEmpty() {
        return attributes.isEmpty();
    }
}
