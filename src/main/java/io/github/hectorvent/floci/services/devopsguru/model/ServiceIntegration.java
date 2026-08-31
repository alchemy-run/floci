package io.github.hectorvent.floci.services.devopsguru.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Account/region DevOps Guru service-integration singleton.
 * Wire names are PascalCase restJson1.
 */
@RegisterForReflection
public class ServiceIntegration {

    public static final String DISABLED = "DISABLED";
    public static final String ENABLED = "ENABLED";
    public static final String AWS_OWNED_KMS_KEY = "AWS_OWNED_KMS_KEY";
    public static final String CUSTOMER_MANAGED_KEY = "CUSTOMER_MANAGED_KEY";

    private String opsCenterOptInStatus = DISABLED;
    private String logsAnomalyDetectionOptInStatus = DISABLED;
    private String encryptionType = AWS_OWNED_KMS_KEY;
    private String kmsKeyId;
    private String encryptionOptInStatus;

    public ServiceIntegration() {
    }

    public ServiceIntegration copy() {
        ServiceIntegration copy = new ServiceIntegration();
        copy.opsCenterOptInStatus = opsCenterOptInStatus;
        copy.logsAnomalyDetectionOptInStatus = logsAnomalyDetectionOptInStatus;
        copy.encryptionType = encryptionType;
        copy.kmsKeyId = kmsKeyId;
        copy.encryptionOptInStatus = encryptionOptInStatus;
        return copy;
    }

    public String getOpsCenterOptInStatus() {
        return opsCenterOptInStatus;
    }

    public void setOpsCenterOptInStatus(String opsCenterOptInStatus) {
        this.opsCenterOptInStatus = opsCenterOptInStatus;
    }

    public String getLogsAnomalyDetectionOptInStatus() {
        return logsAnomalyDetectionOptInStatus;
    }

    public void setLogsAnomalyDetectionOptInStatus(String logsAnomalyDetectionOptInStatus) {
        this.logsAnomalyDetectionOptInStatus = logsAnomalyDetectionOptInStatus;
    }

    public String getEncryptionType() {
        return encryptionType;
    }

    public void setEncryptionType(String encryptionType) {
        this.encryptionType = encryptionType;
    }

    public String getKmsKeyId() {
        return kmsKeyId;
    }

    public void setKmsKeyId(String kmsKeyId) {
        this.kmsKeyId = kmsKeyId;
    }

    public String getEncryptionOptInStatus() {
        return encryptionOptInStatus;
    }

    public void setEncryptionOptInStatus(String encryptionOptInStatus) {
        this.encryptionOptInStatus = encryptionOptInStatus;
    }
}
