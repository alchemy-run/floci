package io.github.hectorvent.floci.services.rolesanywhere.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/** A single Roles Anywhere attribute-mapping specifier. */
@RegisterForReflection
public class MappingRule {
    private String specifier;

    public MappingRule() {
    }

    public MappingRule(String specifier) {
        this.specifier = specifier;
    }

    public String getSpecifier() {
        return specifier;
    }

    public void setSpecifier(String specifier) {
        this.specifier = specifier;
    }
}
