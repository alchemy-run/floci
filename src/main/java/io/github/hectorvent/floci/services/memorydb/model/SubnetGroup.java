package io.github.hectorvent.floci.services.memorydb.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A MemoryDB subnet group: the set of VPC subnets a cluster's nodes are placed into.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class SubnetGroup {

    private String name;
    private String description;
    private String vpcId;
    private List<SubnetRef> subnets = new ArrayList<>();
    private String arn;
    private Instant createdAt;
    private Map<String, String> tags = new LinkedHashMap<>();

    public SubnetGroup() {}

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getVpcId() { return vpcId; }
    public void setVpcId(String vpcId) { this.vpcId = vpcId; }

    public List<SubnetRef> getSubnets() { return subnets; }
    public void setSubnets(List<SubnetRef> subnets) {
        this.subnets = subnets != null ? new ArrayList<>(subnets) : new ArrayList<>();
    }

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? new LinkedHashMap<>(tags) : new LinkedHashMap<>();
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SubnetRef {
        private String identifier;
        private String availabilityZone;

        public SubnetRef() {}

        public SubnetRef(String identifier, String availabilityZone) {
            this.identifier = identifier;
            this.availabilityZone = availabilityZone;
        }

        public String getIdentifier() { return identifier; }
        public void setIdentifier(String identifier) { this.identifier = identifier; }

        public String getAvailabilityZone() { return availabilityZone; }
        public void setAvailabilityZone(String availabilityZone) {
            this.availabilityZone = availabilityZone;
        }
    }
}
