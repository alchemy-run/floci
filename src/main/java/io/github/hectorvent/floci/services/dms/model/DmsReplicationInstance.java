package io.github.hectorvent.floci.services.dms.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Stored DMS replication instance. {@code attributes} is the AWS
 * {@code ReplicationInstance} shape; {@code tags} are returned only via
 * the tagging APIs.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class DmsReplicationInstance {

    private Map<String, Object> attributes = new LinkedHashMap<>();
    private Map<String, String> tags = new LinkedHashMap<>();

    public DmsReplicationInstance() {
    }

    public Map<String, Object> getAttributes() {
        return attributes;
    }

    public void setAttributes(Map<String, Object> attributes) {
        this.attributes = attributes != null ? attributes : new LinkedHashMap<>();
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? tags : new LinkedHashMap<>();
    }

    public String identifier() {
        Object value = attributes.get("ReplicationInstanceIdentifier");
        return value == null ? null : String.valueOf(value);
    }

    public String arn() {
        Object value = attributes.get("ReplicationInstanceArn");
        return value == null ? null : String.valueOf(value);
    }
}
