package io.github.hectorvent.floci.services.codedeploy.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApplicationRevision {
    private Map<String, Object> revisionLocation;
    private Map<String, Object> genericRevisionInfo = new LinkedHashMap<>();

    public Map<String, Object> getRevisionLocation() { return revisionLocation; }
    public void setRevisionLocation(Map<String, Object> revisionLocation) { this.revisionLocation = revisionLocation; }

    public Map<String, Object> getGenericRevisionInfo() { return genericRevisionInfo; }
    public void setGenericRevisionInfo(Map<String, Object> genericRevisionInfo) {
        this.genericRevisionInfo = genericRevisionInfo;
    }
}
