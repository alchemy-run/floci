package io.github.hectorvent.floci.services.codeartifact.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

@RegisterForReflection
public class PackageVersion {

    private String version;
    private String status;
    private String revision;
    private long publishedTime;
    private Map<String, Asset> assets = new LinkedHashMap<>();

    public PackageVersion() {
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getRevision() {
        return revision;
    }

    public void setRevision(String revision) {
        this.revision = revision;
    }

    public long getPublishedTime() {
        return publishedTime;
    }

    public void setPublishedTime(long publishedTime) {
        this.publishedTime = publishedTime;
    }

    public Map<String, Asset> getAssets() {
        return assets;
    }

    public void setAssets(Map<String, Asset> assets) {
        this.assets = assets != null ? assets : new LinkedHashMap<>();
    }
}
