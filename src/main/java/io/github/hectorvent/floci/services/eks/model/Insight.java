package io.github.hectorvent.floci.services.eks.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Insight(
        String id,
        String name,
        String category,
        String kubernetesVersion,
        Double lastRefreshTime,
        Double lastTransitionTime,
        String description,
        InsightStatus insightStatus,
        String recommendation,
        Map<String, String> additionalInfo,
        List<InsightResourceDetail> resources,
        InsightCategorySpecificSummary categorySpecificSummary
) {
    public InsightSummary summary() {
        return new InsightSummary(id, name, category, kubernetesVersion, lastRefreshTime,
                lastTransitionTime, description, insightStatus);
    }
}
