package io.github.hectorvent.floci.services.amazonmq.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory representation of an Amazon MQ configuration. Engine type, version,
 * and authentication strategy are immutable after create; each
 * {@code UpdateConfiguration} appends a new {@link MqConfigurationRevision}.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class MqConfiguration {

    @JsonProperty("id")
    private String id;

    @JsonProperty("arn")
    private String arn;

    @JsonProperty("name")
    private String name;

    @JsonProperty("engineType")
    private String engineType;

    @JsonProperty("engineVersion")
    private String engineVersion;

    @JsonProperty("authenticationStrategy")
    private String authenticationStrategy;

    @JsonProperty("created")
    private Instant created;

    @JsonProperty("tags")
    private Map<String, String> tags = new LinkedHashMap<>();

    @JsonProperty("revisions")
    private List<MqConfigurationRevision> revisions = new ArrayList<>();

    // Internal bookkeeping for account-aware storage. The controller builds
    // DescribeConfiguration responses explicitly so this never leaks.
    private String accountId;

    public MqConfiguration() {}

    public MqConfiguration(String id, String arn, String name, String engineType,
                           String engineVersion, String authenticationStrategy) {
        this.id = id;
        this.arn = arn;
        this.name = name;
        this.engineType = engineType;
        this.engineVersion = engineVersion;
        this.authenticationStrategy = authenticationStrategy;
        this.created = Instant.now();
        this.tags = new LinkedHashMap<>();
        this.revisions = new ArrayList<>();
    }

    public MqConfigurationRevision latestRevision() {
        if (revisions == null || revisions.isEmpty()) {
            return null;
        }
        return revisions.get(revisions.size() - 1);
    }

    public MqConfigurationRevision revision(int number) {
        if (revisions == null) {
            return null;
        }
        for (MqConfigurationRevision revision : revisions) {
            if (revision.getRevision() == number) {
                return revision;
            }
        }
        return null;
    }

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

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }

    public List<MqConfigurationRevision> getRevisions() { return revisions; }
    public void setRevisions(List<MqConfigurationRevision> revisions) { this.revisions = revisions; }

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }
}
