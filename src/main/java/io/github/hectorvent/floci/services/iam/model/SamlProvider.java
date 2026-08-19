package io.github.hectorvent.floci.services.iam.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class SamlProvider {

    private String arn;
    private String name;
    private String uuid;
    private String metadataDocument;
    private String assertionEncryptionMode;
    private Instant createDate;
    private Map<String, String> tags = new ConcurrentHashMap<>();

    public SamlProvider() {}

    public SamlProvider(String arn, String name, String uuid, String metadataDocument,
                        String assertionEncryptionMode) {
        this.arn = arn;
        this.name = name;
        this.uuid = uuid;
        this.metadataDocument = metadataDocument;
        this.assertionEncryptionMode = assertionEncryptionMode;
        this.createDate = Instant.now();
    }

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getUuid() { return uuid; }
    public void setUuid(String uuid) { this.uuid = uuid; }

    public String getMetadataDocument() { return metadataDocument; }
    public void setMetadataDocument(String metadataDocument) { this.metadataDocument = metadataDocument; }

    public String getAssertionEncryptionMode() { return assertionEncryptionMode; }
    public void setAssertionEncryptionMode(String assertionEncryptionMode) {
        this.assertionEncryptionMode = assertionEncryptionMode;
    }

    public Instant getCreateDate() { return createDate; }
    public void setCreateDate(Instant createDate) { this.createDate = createDate; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) {
        this.tags = new ConcurrentHashMap<>(tags);
    }
}
