package io.github.hectorvent.floci.services.configservice.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ConfigurationRecorder(
        @JsonProperty("name") String name,
        @JsonProperty("roleARN") String roleARN,
        @JsonProperty("recordingGroup") RecordingGroup recordingGroup,
        @JsonProperty("recordingMode") Map<String, Object> recordingMode,
        @JsonProperty("arn") String arn) {

    public ConfigurationRecorder(String name, String roleARN, RecordingGroup recordingGroup) {
        this(name, roleARN, recordingGroup, null, null);
    }
}
