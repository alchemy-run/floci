package io.github.hectorvent.floci.services.eks.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class AccessEntry {

    @JsonProperty("clusterName")
    private String clusterName;

    @JsonProperty("principalArn")
    private String principalArn;

    @JsonProperty("kubernetesGroups")
    private List<String> kubernetesGroups = new ArrayList<>();

    @JsonProperty("accessEntryArn")
    private String accessEntryArn;

    @JsonProperty("createdAt")
    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    private Instant createdAt;

    @JsonProperty("modifiedAt")
    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    private Instant modifiedAt;

    @JsonProperty("tags")
    private Map<String, String> tags = new HashMap<>();

    @JsonProperty("username")
    private String username;

    @JsonProperty("type")
    private String type;

    @JsonIgnore
    private String accountId;

    @JsonIgnore
    private List<AssociatedAccessPolicy> associatedAccessPolicies = new ArrayList<>();

    public AccessEntry() {}

    public String getClusterName() { return clusterName; }
    public void setClusterName(String clusterName) { this.clusterName = clusterName; }

    public String getPrincipalArn() { return principalArn; }
    public void setPrincipalArn(String principalArn) { this.principalArn = principalArn; }

    public List<String> getKubernetesGroups() { return kubernetesGroups; }
    public void setKubernetesGroups(List<String> kubernetesGroups) {
        this.kubernetesGroups = kubernetesGroups != null ? kubernetesGroups : new ArrayList<>();
    }

    public String getAccessEntryArn() { return accessEntryArn; }
    public void setAccessEntryArn(String accessEntryArn) { this.accessEntryArn = accessEntryArn; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getModifiedAt() { return modifiedAt; }
    public void setModifiedAt(Instant modifiedAt) { this.modifiedAt = modifiedAt; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? tags : new HashMap<>();
    }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }

    public List<AssociatedAccessPolicy> getAssociatedAccessPolicies() { return associatedAccessPolicies; }
    public void setAssociatedAccessPolicies(List<AssociatedAccessPolicy> associatedAccessPolicies) {
        this.associatedAccessPolicies = associatedAccessPolicies != null
                ? associatedAccessPolicies : new ArrayList<>();
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AccessScope {
        @JsonProperty("type")
        private String type;

        @JsonProperty("namespaces")
        private List<String> namespaces;

        public AccessScope() {}

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }

        public List<String> getNamespaces() { return namespaces; }
        public void setNamespaces(List<String> namespaces) { this.namespaces = namespaces; }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AssociatedAccessPolicy {
        @JsonProperty("policyArn")
        private String policyArn;

        @JsonProperty("accessScope")
        private AccessScope accessScope;

        @JsonProperty("associatedAt")
        @JsonFormat(shape = JsonFormat.Shape.NUMBER)
        private Instant associatedAt;

        @JsonProperty("modifiedAt")
        @JsonFormat(shape = JsonFormat.Shape.NUMBER)
        private Instant modifiedAt;

        public AssociatedAccessPolicy() {}

        public String getPolicyArn() { return policyArn; }
        public void setPolicyArn(String policyArn) { this.policyArn = policyArn; }

        public AccessScope getAccessScope() { return accessScope; }
        public void setAccessScope(AccessScope accessScope) { this.accessScope = accessScope; }

        public Instant getAssociatedAt() { return associatedAt; }
        public void setAssociatedAt(Instant associatedAt) { this.associatedAt = associatedAt; }

        public Instant getModifiedAt() { return modifiedAt; }
        public void setModifiedAt(Instant modifiedAt) { this.modifiedAt = modifiedAt; }
    }
}
