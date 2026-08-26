package io.github.hectorvent.floci.services.rolesanywhere.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

/** IAM Roles Anywhere subject (certificate-identity audit record). */
@RegisterForReflection
public class Subject {
    private String subjectId;
    private String subjectArn;
    private boolean enabled = true;
    private String x509Subject;
    private String lastSeenAt;
    private String createdAt;
    private String updatedAt;
    private List<CredentialSummary> credentials = new ArrayList<>();
    private List<InstanceProperty> instanceProperties = new ArrayList<>();

    public Subject() {
    }

    public String getSubjectId() {
        return subjectId;
    }

    public void setSubjectId(String subjectId) {
        this.subjectId = subjectId;
    }

    public String getSubjectArn() {
        return subjectArn;
    }

    public void setSubjectArn(String subjectArn) {
        this.subjectArn = subjectArn;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getX509Subject() {
        return x509Subject;
    }

    public void setX509Subject(String x509Subject) {
        this.x509Subject = x509Subject;
    }

    public String getLastSeenAt() {
        return lastSeenAt;
    }

    public void setLastSeenAt(String lastSeenAt) {
        this.lastSeenAt = lastSeenAt;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }

    public List<CredentialSummary> getCredentials() {
        return credentials;
    }

    public void setCredentials(List<CredentialSummary> credentials) {
        this.credentials = credentials == null ? new ArrayList<>() : new ArrayList<>(credentials);
    }

    public List<InstanceProperty> getInstanceProperties() {
        return instanceProperties;
    }

    public void setInstanceProperties(List<InstanceProperty> instanceProperties) {
        this.instanceProperties = instanceProperties == null
                ? new ArrayList<>()
                : new ArrayList<>(instanceProperties);
    }

    @RegisterForReflection
    public static class CredentialSummary {
        private String seenAt;
        private String serialNumber;
        private String issuer;
        private boolean enabled = true;
        private String x509CertificateData;
        private boolean failed;

        public String getSeenAt() {
            return seenAt;
        }

        public void setSeenAt(String seenAt) {
            this.seenAt = seenAt;
        }

        public String getSerialNumber() {
            return serialNumber;
        }

        public void setSerialNumber(String serialNumber) {
            this.serialNumber = serialNumber;
        }

        public String getIssuer() {
            return issuer;
        }

        public void setIssuer(String issuer) {
            this.issuer = issuer;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getX509CertificateData() {
            return x509CertificateData;
        }

        public void setX509CertificateData(String x509CertificateData) {
            this.x509CertificateData = x509CertificateData;
        }

        public boolean isFailed() {
            return failed;
        }

        public void setFailed(boolean failed) {
            this.failed = failed;
        }
    }

    @RegisterForReflection
    public static class InstanceProperty {
        private String seenAt;
        private java.util.Map<String, String> properties;
        private boolean failed;

        public String getSeenAt() {
            return seenAt;
        }

        public void setSeenAt(String seenAt) {
            this.seenAt = seenAt;
        }

        public java.util.Map<String, String> getProperties() {
            return properties;
        }

        public void setProperties(java.util.Map<String, String> properties) {
            this.properties = properties;
        }

        public boolean isFailed() {
            return failed;
        }

        public void setFailed(boolean failed) {
            this.failed = failed;
        }
    }
}
