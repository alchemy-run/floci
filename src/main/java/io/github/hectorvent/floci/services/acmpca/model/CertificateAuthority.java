package io.github.hectorvent.floci.services.acmpca.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mutable ACM PCA certificate authority stored by the emulator.
 *
 * @see <a href="https://docs.aws.amazon.com/privateca/latest/APIReference/API_CertificateAuthority.html">CertificateAuthority</a>
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class CertificateAuthority {
    private String arn;
    private String ownerAccount;
    private String region;
    private long createdAt;
    private Long lastStateChangeAt;
    private String type;
    private String status;
    private String usageMode;
    private String keyStorageSecurityStandard;
    private Map<String, Object> certificateAuthorityConfiguration = new LinkedHashMap<>();
    private Map<String, Object> revocationConfiguration = new LinkedHashMap<>();
    private Map<String, String> tags = new LinkedHashMap<>();
    private List<Permission> permissions = new ArrayList<>();
    private String policy;
    private Long restorableUntil;
    private Integer permanentDeletionTimeInDays;
    private String idempotencyToken;
    private String csrPem;
    private String privateKeyPem;
    private String certificatePem;
    private String certificateChainPem;
    private String serial;
    private Long notBefore;
    private Long notAfter;
    private Map<String, IssuedCertificate> certificates = new LinkedHashMap<>();
    private Map<String, AuditReport> auditReports = new LinkedHashMap<>();

    public CertificateAuthority() {
    }

    public String getArn() {
        return arn;
    }

    public void setArn(String arn) {
        this.arn = arn;
    }

    public String getOwnerAccount() {
        return ownerAccount;
    }

    public void setOwnerAccount(String ownerAccount) {
        this.ownerAccount = ownerAccount;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public Long getLastStateChangeAt() {
        return lastStateChangeAt;
    }

    public void setLastStateChangeAt(Long lastStateChangeAt) {
        this.lastStateChangeAt = lastStateChangeAt;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getUsageMode() {
        return usageMode;
    }

    public void setUsageMode(String usageMode) {
        this.usageMode = usageMode;
    }

    public String getKeyStorageSecurityStandard() {
        return keyStorageSecurityStandard;
    }

    public void setKeyStorageSecurityStandard(String keyStorageSecurityStandard) {
        this.keyStorageSecurityStandard = keyStorageSecurityStandard;
    }

    public Map<String, Object> getCertificateAuthorityConfiguration() {
        return certificateAuthorityConfiguration;
    }

    public void setCertificateAuthorityConfiguration(Map<String, Object> certificateAuthorityConfiguration) {
        this.certificateAuthorityConfiguration = certificateAuthorityConfiguration != null
            ? new LinkedHashMap<>(certificateAuthorityConfiguration)
            : new LinkedHashMap<>();
    }

    public Map<String, Object> getRevocationConfiguration() {
        return revocationConfiguration;
    }

    public void setRevocationConfiguration(Map<String, Object> revocationConfiguration) {
        this.revocationConfiguration = revocationConfiguration != null
            ? new LinkedHashMap<>(revocationConfiguration)
            : new LinkedHashMap<>();
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? new LinkedHashMap<>(tags) : new LinkedHashMap<>();
    }

    public List<Permission> getPermissions() {
        return permissions;
    }

    public void setPermissions(List<Permission> permissions) {
        this.permissions = permissions != null ? new ArrayList<>(permissions) : new ArrayList<>();
    }

    public String getPolicy() {
        return policy;
    }

    public void setPolicy(String policy) {
        this.policy = policy;
    }

    public Long getRestorableUntil() {
        return restorableUntil;
    }

    public void setRestorableUntil(Long restorableUntil) {
        this.restorableUntil = restorableUntil;
    }

    public Integer getPermanentDeletionTimeInDays() {
        return permanentDeletionTimeInDays;
    }

    public void setPermanentDeletionTimeInDays(Integer permanentDeletionTimeInDays) {
        this.permanentDeletionTimeInDays = permanentDeletionTimeInDays;
    }

    public String getIdempotencyToken() {
        return idempotencyToken;
    }

    public void setIdempotencyToken(String idempotencyToken) {
        this.idempotencyToken = idempotencyToken;
    }

    public String getCsrPem() {
        return csrPem;
    }

    public void setCsrPem(String csrPem) {
        this.csrPem = csrPem;
    }

    public String getPrivateKeyPem() {
        return privateKeyPem;
    }

    public void setPrivateKeyPem(String privateKeyPem) {
        this.privateKeyPem = privateKeyPem;
    }

    public String getCertificatePem() {
        return certificatePem;
    }

    public void setCertificatePem(String certificatePem) {
        this.certificatePem = certificatePem;
    }

    public String getCertificateChainPem() {
        return certificateChainPem;
    }

    public void setCertificateChainPem(String certificateChainPem) {
        this.certificateChainPem = certificateChainPem;
    }

    public String getSerial() {
        return serial;
    }

    public void setSerial(String serial) {
        this.serial = serial;
    }

    public Long getNotBefore() {
        return notBefore;
    }

    public void setNotBefore(Long notBefore) {
        this.notBefore = notBefore;
    }

    public Long getNotAfter() {
        return notAfter;
    }

    public void setNotAfter(Long notAfter) {
        this.notAfter = notAfter;
    }

    public Map<String, IssuedCertificate> getCertificates() {
        return certificates;
    }

    public void setCertificates(Map<String, IssuedCertificate> certificates) {
        this.certificates = certificates != null ? new LinkedHashMap<>(certificates) : new LinkedHashMap<>();
    }

    public Map<String, AuditReport> getAuditReports() {
        return auditReports;
    }

    public void setAuditReports(Map<String, AuditReport> auditReports) {
        this.auditReports = auditReports != null ? new LinkedHashMap<>(auditReports) : new LinkedHashMap<>();
    }
}
