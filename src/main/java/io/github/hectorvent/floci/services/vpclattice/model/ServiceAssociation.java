package io.github.hectorvent.floci.services.vpclattice.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** A VPC Lattice service-network/service association. */
@RegisterForReflection
public class ServiceAssociation {

    private String id;
    private String arn;
    private String status = "ACTIVE";
    private String createdBy;
    private String serviceId;
    private String serviceName;
    private String serviceArn;
    private String serviceNetworkId;
    private String serviceNetworkName;
    private String serviceNetworkArn;
    private String dnsName;
    private String customDomainName;
    private String region;
    private String createdAt;
    private Map<String, String> tags = new LinkedHashMap<>();

    public ServiceAssociation() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getArn() {
        return arn;
    }

    public void setArn(String arn) {
        this.arn = arn;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public String getServiceId() {
        return serviceId;
    }

    public void setServiceId(String serviceId) {
        this.serviceId = serviceId;
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public String getServiceArn() {
        return serviceArn;
    }

    public void setServiceArn(String serviceArn) {
        this.serviceArn = serviceArn;
    }

    public String getServiceNetworkId() {
        return serviceNetworkId;
    }

    public void setServiceNetworkId(String serviceNetworkId) {
        this.serviceNetworkId = serviceNetworkId;
    }

    public String getServiceNetworkName() {
        return serviceNetworkName;
    }

    public void setServiceNetworkName(String serviceNetworkName) {
        this.serviceNetworkName = serviceNetworkName;
    }

    public String getServiceNetworkArn() {
        return serviceNetworkArn;
    }

    public void setServiceNetworkArn(String serviceNetworkArn) {
        this.serviceNetworkArn = serviceNetworkArn;
    }

    public String getDnsName() {
        return dnsName;
    }

    public void setDnsName(String dnsName) {
        this.dnsName = dnsName;
    }

    public String getCustomDomainName() {
        return customDomainName;
    }

    public void setCustomDomainName(String customDomainName) {
        this.customDomainName = customDomainName;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }
}
