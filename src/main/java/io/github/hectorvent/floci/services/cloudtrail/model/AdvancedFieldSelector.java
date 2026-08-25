package io.github.hectorvent.floci.services.cloudtrail.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
public record AdvancedFieldSelector(
        @JsonProperty("Field") String field,
        @JsonProperty("Equals") List<String> equals,
        @JsonProperty("StartsWith") List<String> startsWith,
        @JsonProperty("EndsWith") List<String> endsWith,
        @JsonProperty("NotEquals") List<String> notEquals,
        @JsonProperty("NotStartsWith") List<String> notStartsWith,
        @JsonProperty("NotEndsWith") List<String> notEndsWith) {
}
