package io.github.hectorvent.floci.services.rolesanywhere.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

/** Mapping from a certificate field to session-tag specifiers. */
@RegisterForReflection
public class AttributeMapping {
    private String certificateField;
    private List<MappingRule> mappingRules = new ArrayList<>();

    public AttributeMapping() {
    }

    public String getCertificateField() {
        return certificateField;
    }

    public void setCertificateField(String certificateField) {
        this.certificateField = certificateField;
    }

    public List<MappingRule> getMappingRules() {
        return mappingRules;
    }

    public void setMappingRules(List<MappingRule> mappingRules) {
        this.mappingRules = mappingRules != null ? mappingRules : new ArrayList<>();
    }
}
