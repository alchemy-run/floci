package io.github.hectorvent.floci.services.iam.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class SshPublicKey {

    private String userName;
    private String sshPublicKeyId;
    private String fingerprint;
    private String sshPublicKeyBody;
    private String status;
    private Instant uploadDate;

    public SshPublicKey() {}

    public SshPublicKey(String userName, String sshPublicKeyId, String fingerprint,
                        String sshPublicKeyBody) {
        this.userName = userName;
        this.sshPublicKeyId = sshPublicKeyId;
        this.fingerprint = fingerprint;
        this.sshPublicKeyBody = sshPublicKeyBody;
        this.status = "Active";
        this.uploadDate = Instant.now();
    }

    public String getUserName() { return userName; }
    public void setUserName(String userName) { this.userName = userName; }

    public String getSshPublicKeyId() { return sshPublicKeyId; }
    public void setSshPublicKeyId(String sshPublicKeyId) { this.sshPublicKeyId = sshPublicKeyId; }

    public String getFingerprint() { return fingerprint; }
    public void setFingerprint(String fingerprint) { this.fingerprint = fingerprint; }

    public String getSshPublicKeyBody() { return sshPublicKeyBody; }
    public void setSshPublicKeyBody(String sshPublicKeyBody) { this.sshPublicKeyBody = sshPublicKeyBody; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Instant getUploadDate() { return uploadDate; }
    public void setUploadDate(Instant uploadDate) { this.uploadDate = uploadDate; }
}
