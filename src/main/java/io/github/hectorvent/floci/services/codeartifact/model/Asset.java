package io.github.hectorvent.floci.services.codeartifact.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class Asset {

    private String name;
    private long size;
    private String sha256;
    private String contentBase64;

    public Asset() {
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        this.size = size;
    }

    public String getSha256() {
        return sha256;
    }

    public void setSha256(String sha256) {
        this.sha256 = sha256;
    }

    public String getContentBase64() {
        return contentBase64;
    }

    public void setContentBase64(String contentBase64) {
        this.contentBase64 = contentBase64;
    }
}
