package io.github.hectorvent.floci.services.iam.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class IamSamlProvider {

    private String arn;
    private String entityId;
    private String certificate;
    private String name;
    private String uuid;
    private String metadataDocument;
    private String assertionEncryptionMode;
    private Instant createDate = Instant.now();
    private Map<String, String> tags = new ConcurrentHashMap<>();

    public IamSamlProvider() {}

    public IamSamlProvider(String arn, String name, String uuid, String metadataDocument,
                        String assertionEncryptionMode) {
        this.arn = arn;
        this.name = name;
        this.uuid = uuid;
        this.metadataDocument = metadataDocument;
        this.assertionEncryptionMode = assertionEncryptionMode;
        this.createDate = Instant.now();
    }

    public String getEntityId() { return entityId; }
    public void setEntityId(String entityId) { this.entityId = entityId; }
    public String getCertificate() { return certificate; }
    public void setCertificate(String certificate) { this.certificate = certificate; }

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
