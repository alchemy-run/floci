package io.github.hectorvent.floci.services.amazonmq.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * An Amazon MQ broker configuration: a named, engine-bound document with an
 * append-only list of immutable revisions. {@code engineType} is stored as the
 * request enum ({@code ACTIVEMQ}/{@code RABBITMQ}); the controller renders the
 * display casing AWS returns.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class MqConfiguration {

    private String id;
    private String arn;
    private String name;
    private String engineType;
    private String engineVersion;
    private String authenticationStrategy;
    private Instant created;
    private String region;
    private Map<String, String> tags = new HashMap<>();
    private List<Revision> revisions = new ArrayList<>();

    public MqConfiguration() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getEngineType() { return engineType; }
    public void setEngineType(String engineType) { this.engineType = engineType; }

    public String getEngineVersion() { return engineVersion; }
    public void setEngineVersion(String engineVersion) { this.engineVersion = engineVersion; }

    public String getAuthenticationStrategy() { return authenticationStrategy; }
    public void setAuthenticationStrategy(String authenticationStrategy) { this.authenticationStrategy = authenticationStrategy; }

    public Instant getCreated() { return created; }
    public void setCreated(Instant created) { this.created = created; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags != null ? tags : new HashMap<>(); }

    public List<Revision> getRevisions() { return revisions; }
    public void setRevisions(List<Revision> revisions) { this.revisions = revisions != null ? revisions : new ArrayList<>(); }

    public Revision latestRevision() {
        return revisions.isEmpty() ? null : revisions.get(revisions.size() - 1);
    }

    public Revision revision(int number) {
        for (Revision revision : revisions) {
            if (revision.getRevision() == number) {
                return revision;
            }
        }
        return null;
    }

    /** One immutable revision; {@code data} is the base64 document exactly as the API returns it. */
    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Revision {

        private int revision;
        private Instant created;
        private String description;
        private String data;

        public Revision() {}

        public Revision(int revision, Instant created, String description, String data) {
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
}
