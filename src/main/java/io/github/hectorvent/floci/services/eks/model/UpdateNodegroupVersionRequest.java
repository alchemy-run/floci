package io.github.hectorvent.floci.services.eks.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record UpdateNodegroupVersionRequest(String version, String releaseVersion, Map<String, Object> launchTemplate,
                                            Boolean force, String clientRequestToken) {}
