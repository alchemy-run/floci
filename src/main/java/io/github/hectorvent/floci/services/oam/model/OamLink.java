package io.github.hectorvent.floci.services.oam.model;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** CloudWatch Observability Access Manager link. */
@RegisterForReflection
public class OamLink {
    private String id;
    private String arn;
    private String label;
    private String labelTemplate;
    private List<String> resourceTypes = new ArrayList<>();
    private String sinkArn;
    private JsonNode linkConfiguration;
    private Map<String, String> tags = new LinkedHashMap<>();

    public OamLink() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getArn() {
        return arn;
    }

    public void setArn(String arn) {
        this.arn = arn;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getLabelTemplate() {
        return labelTemplate;
    }

    public void setLabelTemplate(String labelTemplate) {
        this.labelTemplate = labelTemplate;
    }

    public List<String> getResourceTypes() {
        return resourceTypes;
    }

    public void setResourceTypes(List<String> resourceTypes) {
        this.resourceTypes = resourceTypes == null ? new ArrayList<>() : new ArrayList<>(resourceTypes);
    }

    public String getSinkArn() {
        return sinkArn;
    }

    public void setSinkArn(String sinkArn) {
        this.sinkArn = sinkArn;
    }

    public JsonNode getLinkConfiguration() {
        return linkConfiguration == null ? null : linkConfiguration.deepCopy();
    }

    public void setLinkConfiguration(JsonNode linkConfiguration) {
        this.linkConfiguration = linkConfiguration == null ? null : linkConfiguration.deepCopy();
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }
}
