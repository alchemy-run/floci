package io.github.hectorvent.floci.services.configservice.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record CustomResourceConfig(
        String resourceType,
        String resourceId,
        String resourceName,
        String schemaVersionId,
        String configuration,
        Map<String, String> tags) {
}
