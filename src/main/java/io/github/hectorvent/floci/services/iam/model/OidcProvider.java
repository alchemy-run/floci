package io.github.hectorvent.floci.services.iam.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class OidcProvider {

    private String arn;
    private String url;
    private Instant createDate;
    private List<String> clientIds = new CopyOnWriteArrayList<>();
    private List<String> thumbprints = new CopyOnWriteArrayList<>();
    private Map<String, String> tags = new ConcurrentHashMap<>();

    public OidcProvider() {}

    public OidcProvider(String arn, String url) {
        this.arn = arn;
        this.url = url;
        this.createDate = Instant.now();
    }

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public Instant getCreateDate() { return createDate; }
    public void setCreateDate(Instant createDate) { this.createDate = createDate; }

    public List<String> getClientIds() { return clientIds; }
    public void setClientIds(List<String> clientIds) {
        this.clientIds = new CopyOnWriteArrayList<>(clientIds);
    }

    public List<String> getThumbprints() { return thumbprints; }
    public void setThumbprints(List<String> thumbprints) {
        this.thumbprints = new CopyOnWriteArrayList<>(thumbprints);
    }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) {
        this.tags = new ConcurrentHashMap<>(tags);
    }
}
