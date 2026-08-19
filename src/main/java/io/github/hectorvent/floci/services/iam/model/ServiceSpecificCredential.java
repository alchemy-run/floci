package io.github.hectorvent.floci.services.iam.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class ServiceSpecificCredential {

    private String userName;
    private String serviceName;
    private String serviceSpecificCredentialId;
    private String serviceUserName;
    private String servicePassword;
    private String serviceCredentialAlias;
    private String serviceCredentialSecret;
    private String status;
    private Instant createDate;
    private Instant expirationDate;

    public ServiceSpecificCredential() {}

    public ServiceSpecificCredential(String userName, String serviceName, String id,
                                     String serviceUserName, String servicePassword) {
        this.userName = userName;
        this.serviceName = serviceName;
        this.serviceSpecificCredentialId = id;
        this.serviceUserName = serviceUserName;
        this.servicePassword = servicePassword;
        this.status = "Active";
        this.createDate = Instant.now();
    }

    public String getUserName() { return userName; }
    public void setUserName(String userName) { this.userName = userName; }

    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }

    public String getServiceSpecificCredentialId() { return serviceSpecificCredentialId; }
    public void setServiceSpecificCredentialId(String serviceSpecificCredentialId) {
        this.serviceSpecificCredentialId = serviceSpecificCredentialId;
    }

    public String getServiceUserName() { return serviceUserName; }
    public void setServiceUserName(String serviceUserName) { this.serviceUserName = serviceUserName; }

    public String getServicePassword() { return servicePassword; }
    public void setServicePassword(String servicePassword) { this.servicePassword = servicePassword; }

    public String getServiceCredentialAlias() { return serviceCredentialAlias; }
    public void setServiceCredentialAlias(String serviceCredentialAlias) {
        this.serviceCredentialAlias = serviceCredentialAlias;
    }

    public String getServiceCredentialSecret() { return serviceCredentialSecret; }
    public void setServiceCredentialSecret(String serviceCredentialSecret) {
        this.serviceCredentialSecret = serviceCredentialSecret;
    }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Instant getCreateDate() { return createDate; }
    public void setCreateDate(Instant createDate) { this.createDate = createDate; }

    public Instant getExpirationDate() { return expirationDate; }
    public void setExpirationDate(Instant expirationDate) { this.expirationDate = expirationDate; }
}
