package io.github.hectorvent.floci.services.configservice.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record StoredResourceEvaluation(
        String resourceEvaluationId,
        String evaluationMode,
        long evaluationStartTimestamp,
        String status,
        String resourceId,
        String resourceType,
        String resourceConfiguration,
        String resourceConfigurationSchemaType) {
}
