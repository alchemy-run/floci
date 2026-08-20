package io.github.hectorvent.floci.services.iam.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class ServerCertificate {

    private String serverCertificateName;
    private String serverCertificateId;
    private String arn;
    private String path;
    private String certificateBody;
    private String certificateChain;
    private String privateKey;
    private Instant uploadDate;
    private Instant expiration;
    private Map<String, String> tags = new ConcurrentHashMap<>();

    public ServerCertificate() {}

    public ServerCertificate(String name, String id, String arn, String path,
                             String certificateBody, String certificateChain, String privateKey) {
        this.serverCertificateName = name;
        this.serverCertificateId = id;
        this.arn = arn;
        this.path = path;
        this.certificateBody = certificateBody;
        this.certificateChain = certificateChain;
        this.privateKey = privateKey;
        this.uploadDate = Instant.now();
        this.expiration = Instant.now().plusSeconds(365L * 24 * 3600);
    }

    public String getServerCertificateName() { return serverCertificateName; }
    public void setServerCertificateName(String serverCertificateName) {
        this.serverCertificateName = serverCertificateName;
    }

    public String getServerCertificateId() { return serverCertificateId; }
    public void setServerCertificateId(String serverCertificateId) {
        this.serverCertificateId = serverCertificateId;
    }

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public String getCertificateBody() { return certificateBody; }
    public void setCertificateBody(String certificateBody) { this.certificateBody = certificateBody; }

    public String getCertificateChain() { return certificateChain; }
    public void setCertificateChain(String certificateChain) { this.certificateChain = certificateChain; }

    public String getPrivateKey() { return privateKey; }
    public void setPrivateKey(String privateKey) { this.privateKey = privateKey; }

    public Instant getUploadDate() { return uploadDate; }
    public void setUploadDate(Instant uploadDate) { this.uploadDate = uploadDate; }

    public Instant getExpiration() { return expiration; }
    public void setExpiration(Instant expiration) { this.expiration = expiration; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) {
        this.tags = new ConcurrentHashMap<>(tags);
    }
}
