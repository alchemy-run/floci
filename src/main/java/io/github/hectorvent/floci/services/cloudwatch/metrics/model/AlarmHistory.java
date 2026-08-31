package io.github.hectorvent.floci.services.cloudwatch.metrics.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class AlarmHistory {
    private List<AlarmHistoryItem> items = new ArrayList<>();

    public List<AlarmHistoryItem> getItems() { return items; }
    public void setItems(List<AlarmHistoryItem> items) {
        this.items = items != null ? items : new ArrayList<>();
    }
}
