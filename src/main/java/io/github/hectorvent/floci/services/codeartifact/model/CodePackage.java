package io.github.hectorvent.floci.services.codeartifact.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

@RegisterForReflection
public class CodePackage {

    private String format;
    private String namespace;
    private String name;
    private String domainName;
    private String repositoryName;
    private String publishRestriction;
    private String upstreamRestriction;
    private Map<String, PackageVersion> versions = new LinkedHashMap<>();

    public CodePackage() {
    }

    public String getFormat() {
        return format;
    }

    public void setFormat(String format) {
        this.format = format;
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDomainName() {
        return domainName;
    }

    public void setDomainName(String domainName) {
        this.domainName = domainName;
    }

    public String getRepositoryName() {
        return repositoryName;
    }

    public void setRepositoryName(String repositoryName) {
        this.repositoryName = repositoryName;
    }

    public String getPublishRestriction() {
        return publishRestriction;
    }

    public void setPublishRestriction(String publishRestriction) {
        this.publishRestriction = publishRestriction;
    }

    public String getUpstreamRestriction() {
        return upstreamRestriction;
    }

    public void setUpstreamRestriction(String upstreamRestriction) {
        this.upstreamRestriction = upstreamRestriction;
    }

    public Map<String, PackageVersion> getVersions() {
        return versions;
    }

    public void setVersions(Map<String, PackageVersion> versions) {
        this.versions = versions != null ? versions : new LinkedHashMap<>();
    }
}
