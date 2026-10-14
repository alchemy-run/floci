package io.github.hectorvent.floci.services.eks.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record ListInsightsRequest(
        Filter filter,
        Integer maxResults,
        String nextToken
) {
    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Filter(
            List<String> categories,
            List<String> kubernetesVersions,
            List<String> statuses
    ) {}
}
