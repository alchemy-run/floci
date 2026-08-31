package io.github.hectorvent.floci.services.route53resolver.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ResolverEndpoint {

    private String id;
    private String creatorRequestId;
    private String arn;
    private String name;
    private List<String> securityGroupIds = new ArrayList<>();
    private String direction;
    private String hostVpcId;
    private String status;
    private String statusMessage;
    private String creationTime;
    private String modificationTime;
    private String resolverEndpointType;
    private List<String> protocols = new ArrayList<>();
    private List<ResolverEndpointIpAddress> ipAddresses = new ArrayList<>();
    private Map<String, String> tags = new LinkedHashMap<>();

    public ResolverEndpoint() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getCreatorRequestId() {
        return creatorRequestId;
    }

    public void setCreatorRequestId(String creatorRequestId) {
        this.creatorRequestId = creatorRequestId;
    }

    public String getArn() {
        return arn;
    }

    public void setArn(String arn) {
        this.arn = arn;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<String> getSecurityGroupIds() {
        return securityGroupIds;
    }

    public void setSecurityGroupIds(List<String> securityGroupIds) {
        this.securityGroupIds = securityGroupIds != null ? securityGroupIds : new ArrayList<>();
    }

    public String getDirection() {
        return direction;
    }

    public void setDirection(String direction) {
        this.direction = direction;
    }

    public String getHostVpcId() {
        return hostVpcId;
    }

    public void setHostVpcId(String hostVpcId) {
        this.hostVpcId = hostVpcId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getStatusMessage() {
        return statusMessage;
    }

    public void setStatusMessage(String statusMessage) {
        this.statusMessage = statusMessage;
    }

    public String getCreationTime() {
        return creationTime;
    }

    public void setCreationTime(String creationTime) {
        this.creationTime = creationTime;
    }

    public String getModificationTime() {
        return modificationTime;
    }

    public void setModificationTime(String modificationTime) {
        this.modificationTime = modificationTime;
    }

    public String getResolverEndpointType() {
        return resolverEndpointType;
    }

    public void setResolverEndpointType(String resolverEndpointType) {
        this.resolverEndpointType = resolverEndpointType;
    }

    public List<String> getProtocols() {
        return protocols;
    }

    public void setProtocols(List<String> protocols) {
        this.protocols = protocols != null ? protocols : new ArrayList<>();
    }

    public List<ResolverEndpointIpAddress> getIpAddresses() {
        return ipAddresses;
    }

    public void setIpAddresses(List<ResolverEndpointIpAddress> ipAddresses) {
        this.ipAddresses = ipAddresses != null ? ipAddresses : new ArrayList<>();
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? tags : new LinkedHashMap<>();
    }
}
