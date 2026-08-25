package io.github.hectorvent.floci.services.applicationsignals.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

/** Per-account, per-region Application Signals singleton state. */
@RegisterForReflection
public class AccountSignalsState {

    private boolean discoveryEnabled;
    private List<GroupingAttributeDefinition> groupingAttributeDefinitions;
    private Long groupingUpdatedAt;

    public AccountSignalsState() {
    }

    public boolean isDiscoveryEnabled() {
        return discoveryEnabled;
    }

    public void setDiscoveryEnabled(boolean discoveryEnabled) {
        this.discoveryEnabled = discoveryEnabled;
    }

    public List<GroupingAttributeDefinition> getGroupingAttributeDefinitions() {
        return groupingAttributeDefinitions;
    }

    public void setGroupingAttributeDefinitions(List<GroupingAttributeDefinition> groupingAttributeDefinitions) {
        this.groupingAttributeDefinitions =
                groupingAttributeDefinitions == null ? null : new ArrayList<>(groupingAttributeDefinitions);
    }

    public Long getGroupingUpdatedAt() {
        return groupingUpdatedAt;
    }

    public void setGroupingUpdatedAt(Long groupingUpdatedAt) {
        this.groupingUpdatedAt = groupingUpdatedAt;
    }
}
