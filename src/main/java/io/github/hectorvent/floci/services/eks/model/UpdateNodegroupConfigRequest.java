package io.github.hectorvent.floci.services.eks.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Map;

/** UpdateNodegroupConfig body; labels and taints arrive as add/remove deltas. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record UpdateNodegroupConfigRequest(Labels labels, Taints taints, NodegroupScalingConfig scalingConfig,
                                           Map<String, Object> updateConfig, Map<String, Object> nodeRepairConfig,
                                           String clientRequestToken) {

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Labels(Map<String, String> addOrUpdateLabels, List<String> removeLabels) {}

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Taints(List<Map<String, Object>> addOrUpdateTaints, List<Map<String, Object>> removeTaints) {}
}
