package io.github.hectorvent.floci.services.xray.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/** One segment document belonging to an {@link XRayTrace}. */
@RegisterForReflection
public class XRaySegment {
    private String id;
    private String document;

    public XRaySegment() {
    }

    public XRaySegment(String id, String document) {
        this.id = id;
        this.document = document;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getDocument() {
        return document;
    }

    public void setDocument(String document) {
        this.document = document;
    }
}
