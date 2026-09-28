package io.github.hectorvent.floci.services.eks.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InsightSummary(
        String id,
        String name,
        String category,
        String kubernetesVersion,
        Double lastRefreshTime,
        Double lastTransitionTime,
        String description,
        InsightStatus insightStatus
) {}
