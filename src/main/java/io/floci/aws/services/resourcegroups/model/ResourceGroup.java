package io.floci.aws.services.resourcegroups.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Map;

/**
 * A resource group. A group is defined by a {@link ResourceQuery}, a service {@code configuration},
 * or both; {@code members} are the resources added with {@code GroupResources}, which only a
 * configuration group holds. {@code generation} changes when the group is recreated, so a
 * pagination token of a deleted group never pages a new one of the same name.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record ResourceGroup(String name, String arn, String description, Map<String, String> tags,
                            List<Map<String, Object>> configuration, List<String> members, String generation,
                            ResourceQuery resourceQuery, Integer criticality, String owner, String displayName) {

    public ResourceGroup(String name, String arn, String description, Map<String, String> tags,
                         List<Map<String, Object>> configuration, List<String> members, String generation) {
        this(name, arn, description, tags, configuration, members, generation, null, null, null, null);
    }

    public ResourceGroup withMembers(List<String> updated) {
        return new ResourceGroup(name, arn, description, tags, configuration, List.copyOf(updated), generation,
                resourceQuery, criticality, owner, displayName);
    }

    public ResourceGroup withTags(Map<String, String> updated) {
        return new ResourceGroup(name, arn, description, Map.copyOf(updated), configuration, members, generation,
                resourceQuery, criticality, owner, displayName);
    }

    public ResourceGroup withQuery(ResourceQuery updated) {
        return new ResourceGroup(name, arn, description, tags, configuration, members, generation, updated,
                criticality, owner, displayName);
    }

    public ResourceGroup withConfiguration(List<Map<String, Object>> updated) {
        return new ResourceGroup(name, arn, description, tags, updated, members, generation, resourceQuery,
                criticality, owner, displayName);
    }

    public ResourceGroup withDetails(String updatedDescription, Integer updatedCriticality, String updatedOwner,
                                     String updatedDisplayName) {
        return new ResourceGroup(name, arn, updatedDescription, tags, configuration, members, generation,
                resourceQuery, updatedCriticality, updatedOwner, updatedDisplayName);
    }

    /** Whether the group was created with a service configuration rather than only a query. */
    public boolean hasConfiguration() {
        return configuration != null && !configuration.isEmpty();
    }

    /** A query that selects a group's resources: {@code TAG_FILTERS_1_0} or {@code CLOUDFORMATION_STACK_1_0}. */
    @RegisterForReflection
    public record ResourceQuery(String type, String query) {}
}
