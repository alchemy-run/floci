package io.github.hectorvent.floci.services.elasticache.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
public class CacheSubnetGroup {

    private String cacheSubnetGroupName;
    private String description;
    private List<String> subnetIds = new ArrayList<>();
    private Map<String, String> tags = new LinkedHashMap<>();

    public CacheSubnetGroup() {}

    public CacheSubnetGroup(String cacheSubnetGroupName, String description, List<String> subnetIds, Map<String, String> tags) {
        this.cacheSubnetGroupName = cacheSubnetGroupName;
        this.description = description;
        setSubnetIds(subnetIds);
        setTags(tags);
    }

    public String getCacheSubnetGroupName() { return cacheSubnetGroupName; }
    public void setCacheSubnetGroupName(String cacheSubnetGroupName) { this.cacheSubnetGroupName = cacheSubnetGroupName; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public List<String> getSubnetIds() { return subnetIds; }
    public void setSubnetIds(List<String> subnetIds) { this.subnetIds = subnetIds != null ? new ArrayList<>(subnetIds) : new ArrayList<>(); }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags != null ? new LinkedHashMap<>(tags) : new LinkedHashMap<>(); }
}
