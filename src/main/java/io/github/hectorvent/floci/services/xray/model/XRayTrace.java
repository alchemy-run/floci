package io.github.hectorvent.floci.services.xray.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Stored X-Ray trace assembled from PutTraceSegments documents. */
@RegisterForReflection
public class XRayTrace {
    private String id;
    private double startTime;
    private double endTime;
    private List<XRaySegment> segments = new ArrayList<>();
    private Set<String> serviceNames = new LinkedHashSet<>();

    public XRayTrace() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public double getStartTime() {
        return startTime;
    }

    public void setStartTime(double startTime) {
        this.startTime = startTime;
    }

    public double getEndTime() {
        return endTime;
    }

    public void setEndTime(double endTime) {
        this.endTime = endTime;
    }

    public List<XRaySegment> getSegments() {
        return segments;
    }

    public void setSegments(List<XRaySegment> segments) {
        this.segments = segments == null ? new ArrayList<>() : new ArrayList<>(segments);
    }

    public Set<String> getServiceNames() {
        return serviceNames;
    }

    public void setServiceNames(Set<String> serviceNames) {
        this.serviceNames = serviceNames == null ? new LinkedHashSet<>() : new LinkedHashSet<>(serviceNames);
    }

    public double duration() {
        return Math.max(0.0, endTime - startTime);
    }
}
