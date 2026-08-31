package io.github.hectorvent.floci.services.codeartifact.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A CodeArtifact repository. Wire names are camelCase. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Repository {

    private String name;
    private String administratorAccount;
    private String domainName;
    private String domainOwner;
    private String arn;
    private String description;
    private List<String> upstreams;
    private List<String> externalConnections;
    private long createdTime;
    private String region;
    private Map<String, String> tags;

    public Repository() {
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getAdministratorAccount() {
        return administratorAccount;
    }

    public void setAdministratorAccount(String administratorAccount) {
        this.administratorAccount = administratorAccount;
    }

    public String getDomainName() {
        return domainName;
    }

    public void setDomainName(String domainName) {
        this.domainName = domainName;
    }

    public String getDomainOwner() {
        return domainOwner;
    }

    public void setDomainOwner(String domainOwner) {
        this.domainOwner = domainOwner;
    }

    public String getArn() {
        return arn;
    }

    public void setArn(String arn) {
        this.arn = arn;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public List<String> getUpstreams() {
        return upstreams;
    }

    public void setUpstreams(List<String> upstreams) {
        this.upstreams = upstreams == null ? null : new ArrayList<>(upstreams);
    }

    public List<String> getExternalConnections() {
        return externalConnections;
    }

    public void setExternalConnections(List<String> externalConnections) {
        this.externalConnections = externalConnections == null ? null : new ArrayList<>(externalConnections);
    }

    public long getCreatedTime() {
        return createdTime;
    }

    public void setCreatedTime(long createdTime) {
        this.createdTime = createdTime;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? null : new LinkedHashMap<>(tags);
    }
}
