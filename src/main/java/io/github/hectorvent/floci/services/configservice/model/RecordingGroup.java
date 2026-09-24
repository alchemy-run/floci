package io.github.hectorvent.floci.services.configservice.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record RecordingGroup(
        @JsonProperty("allSupported") Boolean allSupported,
        @JsonProperty("includeGlobalResourceTypes") Boolean includeGlobalResourceTypes,
        @JsonProperty("resourceTypes") List<String> resourceTypes,
        @JsonProperty("exclusionByResourceTypes") Map<String, List<String>> exclusionByResourceTypes,
        @JsonProperty("recordingStrategy") Map<String, String> recordingStrategy) {

    public RecordingGroup(Boolean allSupported, Boolean includeGlobalResourceTypes, List<String> resourceTypes) {
        this(allSupported, includeGlobalResourceTypes, resourceTypes, null, null);
    }
}
