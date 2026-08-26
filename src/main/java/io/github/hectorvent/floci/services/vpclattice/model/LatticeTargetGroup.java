package io.github.hectorvent.floci.services.vpclattice.model;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A VPC Lattice target group. */
@RegisterForReflection
public class LatticeTargetGroup {

    private String id;
    private String arn;
    private String name;
    private String type;
    private String status = "ACTIVE";
    private JsonNode config;
    private List<LatticeTarget> targets = new ArrayList<>();
    private String region;
    private String createdAt;
    private String lastUpdatedAt;
    private Map<String, String> tags = new LinkedHashMap<>();

    public LatticeTargetGroup() {
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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public JsonNode getConfig() {
        return config;
    }

    public void setConfig(JsonNode config) {
        this.config = config;
    }

    public List<LatticeTarget> getTargets() {
        return targets;
    }

    public void setTargets(List<LatticeTarget> targets) {
        this.targets = targets == null ? new ArrayList<>() : new ArrayList<>(targets);
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

    public String getLastUpdatedAt() {
        return lastUpdatedAt;
    }

    public void setLastUpdatedAt(String lastUpdatedAt) {
        this.lastUpdatedAt = lastUpdatedAt;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }
}
