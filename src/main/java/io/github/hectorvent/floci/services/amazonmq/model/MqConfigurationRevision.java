package io.github.hectorvent.floci.services.amazonmq.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

/**
 * One immutable revision of an Amazon MQ configuration document. {@code data} is
 * stored as the decoded (UTF-8) document; the controller base64-encodes it on
 * {@code DescribeConfigurationRevision}.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class MqConfigurationRevision {

    @JsonProperty("revision")
    private int revision;

    @JsonProperty("created")
    private Instant created;

    @JsonProperty("description")
    private String description;

    @JsonProperty("data")
    private String data;

    public MqConfigurationRevision() {}

    public MqConfigurationRevision(int revision, Instant created, String description, String data) {
        this.revision = revision;
        this.created = created;
        this.description = description;
        this.data = data;
    }

    public int getRevision() { return revision; }
    public void setRevision(int revision) { this.revision = revision; }

    public Instant getCreated() { return created; }
    public void setCreated(Instant created) { this.created = created; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getData() { return data; }
    public void setData(String data) { this.data = data; }
}
