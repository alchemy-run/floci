package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** One authentication method of a Client VPN endpoint. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class ClientVpnAuthentication {

    private String type;
    private String directoryId;
    private String clientRootCertificateChainArn;
    private String samlProviderArn;
    private String selfServiceSamlProviderArn;

    public ClientVpnAuthentication() {}

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getDirectoryId() { return directoryId; }
    public void setDirectoryId(String directoryId) { this.directoryId = directoryId; }

    public String getClientRootCertificateChainArn() { return clientRootCertificateChainArn; }
    public void setClientRootCertificateChainArn(String clientRootCertificateChainArn) {
        this.clientRootCertificateChainArn = clientRootCertificateChainArn;
    }

    public String getSamlProviderArn() { return samlProviderArn; }
    public void setSamlProviderArn(String samlProviderArn) { this.samlProviderArn = samlProviderArn; }

    public String getSelfServiceSamlProviderArn() { return selfServiceSamlProviderArn; }
    public void setSelfServiceSamlProviderArn(String selfServiceSamlProviderArn) {
        this.selfServiceSamlProviderArn = selfServiceSamlProviderArn;
    }
}
