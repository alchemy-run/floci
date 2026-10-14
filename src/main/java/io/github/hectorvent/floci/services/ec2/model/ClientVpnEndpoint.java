package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

/**
 * A Client VPN endpoint together with the sub-resources AWS scopes to it: target network
 * associations, authorization rules and routes. Keeping them on one record lets every mutation
 * of an endpoint's state be a single read-modify-write under the endpoint's lock.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class ClientVpnEndpoint {

    private String clientVpnEndpointId;
    private String region;
    private String ownerId;
    private String description;
    private String status;
    private String creationTime;
    private String dnsName;
    private String clientCidrBlock;
    private List<String> dnsServers = new ArrayList<>();
    private boolean splitTunnel;
    private String transportProtocol;
    private int vpnPort;
    private String serverCertificateArn;
    private List<ClientVpnAuthentication> authenticationOptions = new ArrayList<>();
    private boolean connectionLogEnabled;
    private String connectionLogGroup;
    private String connectionLogStream;
    private List<String> securityGroupIds = new ArrayList<>();
    private String vpcId;
    private String selfServicePortal;
    private boolean clientConnectEnabled;
    private String clientConnectLambdaFunctionArn;
    private int sessionTimeoutHours;
    private boolean clientLoginBannerEnabled;
    private String clientLoginBannerText;
    private boolean clientRouteEnforced;
    private boolean disconnectOnSessionTimeout;
    private String endpointIpAddressType;
    private String trafficIpAddressType;
    private String clientToken;
    private List<ClientVpnTargetNetwork> targetNetworks = new ArrayList<>();
    private List<ClientVpnAuthorizationRule> authorizationRules = new ArrayList<>();
    private List<ClientVpnRoute> routes = new ArrayList<>();

    public ClientVpnEndpoint() {}

    public String getClientVpnEndpointId() { return clientVpnEndpointId; }
    public void setClientVpnEndpointId(String clientVpnEndpointId) { this.clientVpnEndpointId = clientVpnEndpointId; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getCreationTime() { return creationTime; }
    public void setCreationTime(String creationTime) { this.creationTime = creationTime; }

    public String getDnsName() { return dnsName; }
    public void setDnsName(String dnsName) { this.dnsName = dnsName; }

    public String getClientCidrBlock() { return clientCidrBlock; }
    public void setClientCidrBlock(String clientCidrBlock) { this.clientCidrBlock = clientCidrBlock; }

    public List<String> getDnsServers() { return dnsServers; }
    public void setDnsServers(List<String> dnsServers) { this.dnsServers = dnsServers; }

    public boolean isSplitTunnel() { return splitTunnel; }
    public void setSplitTunnel(boolean splitTunnel) { this.splitTunnel = splitTunnel; }

    public String getTransportProtocol() { return transportProtocol; }
    public void setTransportProtocol(String transportProtocol) { this.transportProtocol = transportProtocol; }

    public int getVpnPort() { return vpnPort; }
    public void setVpnPort(int vpnPort) { this.vpnPort = vpnPort; }

    public String getServerCertificateArn() { return serverCertificateArn; }
    public void setServerCertificateArn(String serverCertificateArn) { this.serverCertificateArn = serverCertificateArn; }

    public List<ClientVpnAuthentication> getAuthenticationOptions() { return authenticationOptions; }
    public void setAuthenticationOptions(List<ClientVpnAuthentication> authenticationOptions) {
        this.authenticationOptions = authenticationOptions;
    }

    public boolean isConnectionLogEnabled() { return connectionLogEnabled; }
    public void setConnectionLogEnabled(boolean connectionLogEnabled) { this.connectionLogEnabled = connectionLogEnabled; }

    public String getConnectionLogGroup() { return connectionLogGroup; }
    public void setConnectionLogGroup(String connectionLogGroup) { this.connectionLogGroup = connectionLogGroup; }

    public String getConnectionLogStream() { return connectionLogStream; }
    public void setConnectionLogStream(String connectionLogStream) { this.connectionLogStream = connectionLogStream; }

    public List<String> getSecurityGroupIds() { return securityGroupIds; }
    public void setSecurityGroupIds(List<String> securityGroupIds) { this.securityGroupIds = securityGroupIds; }

    public String getVpcId() { return vpcId; }
    public void setVpcId(String vpcId) { this.vpcId = vpcId; }

    public String getSelfServicePortal() { return selfServicePortal; }
    public void setSelfServicePortal(String selfServicePortal) { this.selfServicePortal = selfServicePortal; }

    public boolean isClientConnectEnabled() { return clientConnectEnabled; }
    public void setClientConnectEnabled(boolean clientConnectEnabled) { this.clientConnectEnabled = clientConnectEnabled; }

    public String getClientConnectLambdaFunctionArn() { return clientConnectLambdaFunctionArn; }
    public void setClientConnectLambdaFunctionArn(String clientConnectLambdaFunctionArn) {
        this.clientConnectLambdaFunctionArn = clientConnectLambdaFunctionArn;
    }

    public int getSessionTimeoutHours() { return sessionTimeoutHours; }
    public void setSessionTimeoutHours(int sessionTimeoutHours) { this.sessionTimeoutHours = sessionTimeoutHours; }

    public boolean isClientLoginBannerEnabled() { return clientLoginBannerEnabled; }
    public void setClientLoginBannerEnabled(boolean clientLoginBannerEnabled) {
        this.clientLoginBannerEnabled = clientLoginBannerEnabled;
    }

    public String getClientLoginBannerText() { return clientLoginBannerText; }
    public void setClientLoginBannerText(String clientLoginBannerText) { this.clientLoginBannerText = clientLoginBannerText; }

    public boolean isClientRouteEnforced() { return clientRouteEnforced; }
    public void setClientRouteEnforced(boolean clientRouteEnforced) { this.clientRouteEnforced = clientRouteEnforced; }

    public boolean isDisconnectOnSessionTimeout() { return disconnectOnSessionTimeout; }
    public void setDisconnectOnSessionTimeout(boolean disconnectOnSessionTimeout) {
        this.disconnectOnSessionTimeout = disconnectOnSessionTimeout;
    }

    public String getEndpointIpAddressType() { return endpointIpAddressType; }
    public void setEndpointIpAddressType(String endpointIpAddressType) { this.endpointIpAddressType = endpointIpAddressType; }

    public String getTrafficIpAddressType() { return trafficIpAddressType; }
    public void setTrafficIpAddressType(String trafficIpAddressType) { this.trafficIpAddressType = trafficIpAddressType; }

    public String getClientToken() { return clientToken; }
    public void setClientToken(String clientToken) { this.clientToken = clientToken; }

    public List<ClientVpnTargetNetwork> getTargetNetworks() { return targetNetworks; }
    public void setTargetNetworks(List<ClientVpnTargetNetwork> targetNetworks) { this.targetNetworks = targetNetworks; }

    public List<ClientVpnAuthorizationRule> getAuthorizationRules() { return authorizationRules; }
    public void setAuthorizationRules(List<ClientVpnAuthorizationRule> authorizationRules) {
        this.authorizationRules = authorizationRules;
    }

    public List<ClientVpnRoute> getRoutes() { return routes; }
    public void setRoutes(List<ClientVpnRoute> routes) { this.routes = routes; }
}
