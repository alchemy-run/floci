package io.github.hectorvent.floci.services.accessanalyzer.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

/** A single archive-rule filter criterion. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Criterion {

    private List<String> eq;
    private List<String> neq;
    private List<String> contains;
    private Boolean exists;

    public Criterion() {
    }

    public List<String> getEq() {
        return eq;
    }

    public void setEq(List<String> eq) {
        this.eq = eq == null ? null : new ArrayList<>(eq);
    }

    public List<String> getNeq() {
        return neq;
    }

    public void setNeq(List<String> neq) {
        this.neq = neq == null ? null : new ArrayList<>(neq);
    }

    public List<String> getContains() {
        return contains;
    }

    public void setContains(List<String> contains) {
        this.contains = contains == null ? null : new ArrayList<>(contains);
    }

    public Boolean getExists() {
        return exists;
    }

    public void setExists(Boolean exists) {
        this.exists = exists;
    }
}
