package io.github.hectorvent.floci.services.dms.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Stored DMS endpoint. {@code attributes} is the AWS {@code Endpoint} shape
 * (no password); {@code tags} are returned only via the tagging APIs.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class DmsEndpoint {

    private Map<String, Object> attributes = new LinkedHashMap<>();
    private String password;
    private Map<String, String> tags = new LinkedHashMap<>();

    public DmsEndpoint() {
    }

    public Map<String, Object> getAttributes() {
        return attributes;
    }

    public void setAttributes(Map<String, Object> attributes) {
        this.attributes = attributes != null ? attributes : new LinkedHashMap<>();
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? tags : new LinkedHashMap<>();
    }

    public String identifier() {
        Object value = attributes.get("EndpointIdentifier");
        return value == null ? null : String.valueOf(value);
    }

    public String arn() {
        Object value = attributes.get("EndpointArn");
        return value == null ? null : String.valueOf(value);
    }
}
