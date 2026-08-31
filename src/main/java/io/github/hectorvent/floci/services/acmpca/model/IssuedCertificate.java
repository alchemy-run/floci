package io.github.hectorvent.floci.services.acmpca.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * A certificate issued by a private CA.
 *
 * @see <a href="https://docs.aws.amazon.com/privateca/latest/APIReference/API_IssueCertificate.html">IssueCertificate</a>
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class IssuedCertificate {
    private String arn;
    private String pem;
    private String chainPem;
    private String serialHex;
    private String status;
    private String revocationReason;
    private long issuedAt;

    public IssuedCertificate() {
    }

    public String getArn() {
        return arn;
    }

    public void setArn(String arn) {
        this.arn = arn;
    }

    public String getPem() {
        return pem;
    }

    public void setPem(String pem) {
        this.pem = pem;
    }

    public String getChainPem() {
        return chainPem;
    }

    public void setChainPem(String chainPem) {
        this.chainPem = chainPem;
    }

    public String getSerialHex() {
        return serialHex;
    }

    public void setSerialHex(String serialHex) {
        this.serialHex = serialHex;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getRevocationReason() {
        return revocationReason;
    }

    public void setRevocationReason(String revocationReason) {
        this.revocationReason = revocationReason;
    }

    public long getIssuedAt() {
        return issuedAt;
    }

    public void setIssuedAt(long issuedAt) {
        this.issuedAt = issuedAt;
    }
}
