package io.github.hectorvent.floci.services.ram.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RegisterForReflection
public class ResourceShare {

    private final String resourceShareArn;
    private final String name;
    private final String owningAccountId;
    private final List<String> principals;
    private final List<String> resourceArns;
    private final boolean allowExternalPrincipals;
    private final String status;
    private final Instant creationTime;
    private final Instant lastUpdatedTime;
    private final Map<String, String> tags;
    private final List<String> sources;
    private final List<String> permissionArns;

    public ResourceShare(String resourceShareArn, String name, String owningAccountId,
                         List<String> principals, List<String> resourceArns,
                         boolean allowExternalPrincipals) {
        this(resourceShareArn, name, owningAccountId, principals, resourceArns,
                allowExternalPrincipals, "ACTIVE", Instant.now(), null, Map.of(), List.of(), List.of());
    }

    @JsonCreator
    public ResourceShare(
            @JsonProperty("resourceShareArn") String resourceShareArn,
            @JsonProperty("name") String name,
            @JsonProperty("owningAccountId") String owningAccountId,
            @JsonProperty("principals") List<String> principals,
            @JsonProperty("resourceArns") List<String> resourceArns,
            @JsonProperty("allowExternalPrincipals") boolean allowExternalPrincipals,
            @JsonProperty("status") String status,
            @JsonProperty("creationTime") Instant creationTime,
            @JsonProperty("lastUpdatedTime") Instant lastUpdatedTime,
            @JsonProperty("tags") Map<String, String> tags,
            @JsonProperty("sources") List<String> sources,
            @JsonProperty("permissionArns") List<String> permissionArns) {
        this.resourceShareArn = resourceShareArn;
        this.name = name;
        this.owningAccountId = owningAccountId;
        this.principals = List.copyOf(principals);
        this.resourceArns = List.copyOf(resourceArns);
        this.allowExternalPrincipals = allowExternalPrincipals;
        this.status = status;
        this.creationTime = creationTime;
        // State persisted before lastUpdatedTime existed has never been mutated since it was
        // written, so creation time is its correct last-updated value.
        this.lastUpdatedTime = lastUpdatedTime == null ? creationTime : lastUpdatedTime;
        this.tags = tags == null ? Map.of() : Map.copyOf(tags);
        // State persisted before sources/permissionArns were modelled had neither.
        this.sources = sources == null ? List.of() : List.copyOf(sources);
        this.permissionArns = permissionArns == null ? List.of() : List.copyOf(permissionArns);
    }

    public String getResourceShareArn() { return resourceShareArn; }
    public String getName() { return name; }
    public String getOwningAccountId() { return owningAccountId; }
    public List<String> getPrincipals() { return principals; }
    public List<String> getResourceArns() { return resourceArns; }
    public boolean isAllowExternalPrincipals() { return allowExternalPrincipals; }
    public String getStatus() { return status; }
    public Instant getCreationTime() { return creationTime; }
    public Instant getLastUpdatedTime() { return lastUpdatedTime; }
    public Map<String, String> getTags() { return tags; }
    public List<String> getSources() { return sources; }
    public List<String> getPermissionArns() { return permissionArns; }

    private ResourceShare copy(String newName, List<String> newPrincipals, List<String> newResourceArns,
                               boolean newAllowExternal, String newStatus, Instant newLastUpdated,
                               Map<String, String> newTags, List<String> newSources,
                               List<String> newPermissionArns) {
        return new ResourceShare(resourceShareArn, newName, owningAccountId, newPrincipals, newResourceArns,
                newAllowExternal, newStatus, creationTime, newLastUpdated, newTags, newSources,
                newPermissionArns);
    }

    public ResourceShare withName(String newName) {
        return copy(newName, principals, resourceArns, allowExternalPrincipals, status, lastUpdatedTime,
                tags, sources, permissionArns);
    }

    public ResourceShare withAllowExternalPrincipals(boolean value) {
        return copy(name, principals, resourceArns, value, status, lastUpdatedTime,
                tags, sources, permissionArns);
    }

    public ResourceShare withStatus(String newStatus) {
        return copy(name, principals, resourceArns, allowExternalPrincipals, newStatus, lastUpdatedTime,
                tags, sources, permissionArns);
    }

    public ResourceShare withPrincipalsAndResources(List<String> newPrincipals, List<String> newResourceArns) {
        return copy(name, newPrincipals, newResourceArns, allowExternalPrincipals, status, lastUpdatedTime,
                tags, sources, permissionArns);
    }

    public ResourceShare withSources(List<String> newSources) {
        return copy(name, principals, resourceArns, allowExternalPrincipals, status, lastUpdatedTime,
                tags, newSources, permissionArns);
    }

    public ResourceShare withPermissionArns(List<String> newPermissionArns) {
        return copy(name, principals, resourceArns, allowExternalPrincipals, status, lastUpdatedTime,
                tags, sources, newPermissionArns);
    }

    public ResourceShare withLastUpdatedTime(Instant stamp) {
        return copy(name, principals, resourceArns, allowExternalPrincipals, status, stamp,
                tags, sources, permissionArns);
    }

    public ResourceShare withTags(Map<String, String> newTags) {
        return copy(name, principals, resourceArns, allowExternalPrincipals, status, lastUpdatedTime,
                newTags, sources, permissionArns);
    }
}
