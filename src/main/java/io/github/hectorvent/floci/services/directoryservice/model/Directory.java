package io.github.hectorvent.floci.services.directoryservice.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class Directory {

    private String directoryId;
    private String region;
    private String name;
    private String shortName;
    private String password;
    private String description;
    private String size;
    private String edition;
    private String type;
    private String stage;
    private String alias;
    private String accessUrl;
    private String vpcId;
    private String securityGroupId;
    private long launchTime;
    private List<String> subnetIds = new ArrayList<>();
    private List<String> availabilityZones = new ArrayList<>();
    private List<String> dnsIpAddrs = new ArrayList<>();
    private Map<String, String> tags = new LinkedHashMap<>();
    private Map<String, DirectoryEventTopic> eventTopics = new LinkedHashMap<>();
    private Map<String, DirectoryForwarder> forwarders = new LinkedHashMap<>();

    public Directory() {
    }

    public String getDirectoryId() {
        return directoryId;
    }

    public void setDirectoryId(String directoryId) {
        this.directoryId = directoryId;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getShortName() {
        return shortName;
    }

    public void setShortName(String shortName) {
        this.shortName = shortName;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getSize() {
        return size;
    }

    public void setSize(String size) {
        this.size = size;
    }

    public String getEdition() {
        return edition;
    }

    public void setEdition(String edition) {
        this.edition = edition;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getStage() {
        return stage;
    }

    public void setStage(String stage) {
        this.stage = stage;
    }

    public String getAlias() {
        return alias;
    }

    public void setAlias(String alias) {
        this.alias = alias;
    }

    public String getAccessUrl() {
        return accessUrl;
    }

    public void setAccessUrl(String accessUrl) {
        this.accessUrl = accessUrl;
    }

    public String getVpcId() {
        return vpcId;
    }

    public void setVpcId(String vpcId) {
        this.vpcId = vpcId;
    }

    public String getSecurityGroupId() {
        return securityGroupId;
    }

    public void setSecurityGroupId(String securityGroupId) {
        this.securityGroupId = securityGroupId;
    }

    public long getLaunchTime() {
        return launchTime;
    }

    public void setLaunchTime(long launchTime) {
        this.launchTime = launchTime;
    }

    public List<String> getSubnetIds() {
        return subnetIds;
    }

    public void setSubnetIds(List<String> subnetIds) {
        this.subnetIds = subnetIds != null ? subnetIds : new ArrayList<>();
    }

    public List<String> getAvailabilityZones() {
        return availabilityZones;
    }

    public void setAvailabilityZones(List<String> availabilityZones) {
        this.availabilityZones = availabilityZones != null ? availabilityZones : new ArrayList<>();
    }

    public List<String> getDnsIpAddrs() {
        return dnsIpAddrs;
    }

    public void setDnsIpAddrs(List<String> dnsIpAddrs) {
        this.dnsIpAddrs = dnsIpAddrs != null ? dnsIpAddrs : new ArrayList<>();
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? tags : new LinkedHashMap<>();
    }

    public Map<String, DirectoryEventTopic> getEventTopics() {
        return eventTopics;
    }

    public void setEventTopics(Map<String, DirectoryEventTopic> eventTopics) {
        this.eventTopics = eventTopics != null ? eventTopics : new LinkedHashMap<>();
    }

    public Map<String, DirectoryForwarder> getForwarders() {
        return forwarders;
    }

    public void setForwarders(Map<String, DirectoryForwarder> forwarders) {
        this.forwarders = forwarders != null ? forwarders : new LinkedHashMap<>();
    }
}
