package io.github.hectorvent.floci.services.simpledb.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

@RegisterForReflection
public class SimpleDbDomain {

    private String domainName;
    private long createdAtEpochSeconds;
    private Map<String, SimpleDbItem> items = new LinkedHashMap<>();

    public String getDomainName() {
        return domainName;
    }

    public void setDomainName(String domainName) {
        this.domainName = domainName;
    }

    public long getCreatedAtEpochSeconds() {
        return createdAtEpochSeconds;
    }

    public void setCreatedAtEpochSeconds(long createdAtEpochSeconds) {
        this.createdAtEpochSeconds = createdAtEpochSeconds;
    }

    public Map<String, SimpleDbItem> getItems() {
        return items;
    }

    public void setItems(Map<String, SimpleDbItem> items) {
        this.items = items != null ? items : new LinkedHashMap<>();
    }
}
