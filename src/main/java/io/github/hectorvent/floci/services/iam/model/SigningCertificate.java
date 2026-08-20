package io.github.hectorvent.floci.services.iam.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class SigningCertificate {

    private String userName;
    private String certificateId;
    private String certificateBody;
    private String status;
    private Instant uploadDate;

    public SigningCertificate() {}

    public SigningCertificate(String userName, String certificateId, String certificateBody) {
        this.userName = userName;
        this.certificateId = certificateId;
        this.certificateBody = certificateBody;
        this.status = "Active";
        this.uploadDate = Instant.now();
    }

    public String getUserName() { return userName; }
    public void setUserName(String userName) { this.userName = userName; }

    public String getCertificateId() { return certificateId; }
    public void setCertificateId(String certificateId) { this.certificateId = certificateId; }

    public String getCertificateBody() { return certificateBody; }
    public void setCertificateBody(String certificateBody) { this.certificateBody = certificateBody; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Instant getUploadDate() { return uploadDate; }
    public void setUploadDate(Instant uploadDate) { this.uploadDate = uploadDate; }
}
