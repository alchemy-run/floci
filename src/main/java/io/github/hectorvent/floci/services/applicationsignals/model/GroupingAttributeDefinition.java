package io.github.hectorvent.floci.services.applicationsignals.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

/** A CloudWatch Application Signals grouping attribute definition (PascalCase restJson1). */
@RegisterForReflection
public class GroupingAttributeDefinition {

    private String groupingName;
    private List<String> groupingSourceKeys;
    private String defaultGroupingValue;

    public GroupingAttributeDefinition() {
    }

    public String getGroupingName() {
        return groupingName;
    }

    public void setGroupingName(String groupingName) {
        this.groupingName = groupingName;
    }

    public List<String> getGroupingSourceKeys() {
        return groupingSourceKeys;
    }

    public void setGroupingSourceKeys(List<String> groupingSourceKeys) {
        this.groupingSourceKeys = groupingSourceKeys == null ? null : new ArrayList<>(groupingSourceKeys);
    }

    public String getDefaultGroupingValue() {
        return defaultGroupingValue;
    }

    public void setDefaultGroupingValue(String defaultGroupingValue) {
        this.defaultGroupingValue = defaultGroupingValue;
    }
}
