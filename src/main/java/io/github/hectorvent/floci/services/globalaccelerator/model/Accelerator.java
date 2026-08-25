package io.github.hectorvent.floci.services.globalaccelerator.model;

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
public class Accelerator {

    private String acceleratorArn;
    private String name;
    private String ipAddressType;
    private boolean enabled = true;
    private List<String> ipv4Addresses = new ArrayList<>();
    private List<String> ipv6Addresses = new ArrayList<>();
    private String dnsName;
    private String dualStackDnsName;
    private String status;
    private long createdTime;
    private long lastModifiedTime;
    private boolean flowLogsEnabled;
    private String flowLogsS3Bucket;
    private String flowLogsS3Prefix;
    private String idempotencyToken;
    private Map<String, String> tags = new LinkedHashMap<>();

    public Accelerator() {
    }

    public String getAcceleratorArn() {
        return acceleratorArn;
    }

    public void setAcceleratorArn(String acceleratorArn) {
        this.acceleratorArn = acceleratorArn;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getIpAddressType() {
        return ipAddressType;
    }

    public void setIpAddressType(String ipAddressType) {
        this.ipAddressType = ipAddressType;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getIpv4Addresses() {
        return ipv4Addresses;
    }

    public void setIpv4Addresses(List<String> ipv4Addresses) {
        this.ipv4Addresses = ipv4Addresses != null ? ipv4Addresses : new ArrayList<>();
    }

    public List<String> getIpv6Addresses() {
        return ipv6Addresses;
    }

    public void setIpv6Addresses(List<String> ipv6Addresses) {
        this.ipv6Addresses = ipv6Addresses != null ? ipv6Addresses : new ArrayList<>();
    }

    public String getDnsName() {
        return dnsName;
    }

    public void setDnsName(String dnsName) {
        this.dnsName = dnsName;
    }

    public String getDualStackDnsName() {
        return dualStackDnsName;
    }

    public void setDualStackDnsName(String dualStackDnsName) {
        this.dualStackDnsName = dualStackDnsName;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public long getCreatedTime() {
        return createdTime;
    }

    public void setCreatedTime(long createdTime) {
        this.createdTime = createdTime;
    }

    public long getLastModifiedTime() {
        return lastModifiedTime;
    }

    public void setLastModifiedTime(long lastModifiedTime) {
        this.lastModifiedTime = lastModifiedTime;
    }

    public boolean isFlowLogsEnabled() {
        return flowLogsEnabled;
    }

    public void setFlowLogsEnabled(boolean flowLogsEnabled) {
        this.flowLogsEnabled = flowLogsEnabled;
    }

    public String getFlowLogsS3Bucket() {
        return flowLogsS3Bucket;
    }

    public void setFlowLogsS3Bucket(String flowLogsS3Bucket) {
        this.flowLogsS3Bucket = flowLogsS3Bucket;
    }

    public String getFlowLogsS3Prefix() {
        return flowLogsS3Prefix;
    }

    public void setFlowLogsS3Prefix(String flowLogsS3Prefix) {
        this.flowLogsS3Prefix = flowLogsS3Prefix;
    }

    public String getIdempotencyToken() {
        return idempotencyToken;
    }

    public void setIdempotencyToken(String idempotencyToken) {
        this.idempotencyToken = idempotencyToken;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? tags : new LinkedHashMap<>();
    }
}
