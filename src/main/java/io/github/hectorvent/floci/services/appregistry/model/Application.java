package io.github.hectorvent.floci.services.appregistry.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** An AWS Service Catalog AppRegistry application. Wire names are camelCase restJson1. */
@RegisterForReflection
public class Application {

    private String id;
    private String arn;
    private String name;
    private String description;
    private String clientToken;
    private Map<String, String> tags = new LinkedHashMap<>();
    private Map<String, String> applicationTag = new LinkedHashMap<>();
    private Set<String> associatedAttributeGroupIds = new LinkedHashSet<>();
    private List<AssociatedResource> associatedResources = new ArrayList<>();
    private String creationTime;
    private String lastUpdateTime;

    public Application() {
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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getClientToken() {
        return clientToken;
    }

    public void setClientToken(String clientToken) {
        this.clientToken = clientToken;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }

    public Map<String, String> getApplicationTag() {
        if ((applicationTag == null || applicationTag.isEmpty()) && arn != null) {
            Map<String, String> tag = new LinkedHashMap<>();
            tag.put("awsApplication", arn);
            return tag;
        }
        return applicationTag;
    }

    public void setApplicationTag(Map<String, String> applicationTag) {
        this.applicationTag = applicationTag == null ? new LinkedHashMap<>() : new LinkedHashMap<>(applicationTag);
    }

    public Set<String> getAssociatedAttributeGroupIds() {
        return associatedAttributeGroupIds;
    }

    public void setAssociatedAttributeGroupIds(Set<String> associatedAttributeGroupIds) {
        this.associatedAttributeGroupIds = associatedAttributeGroupIds == null
                ? new LinkedHashSet<>()
                : new LinkedHashSet<>(associatedAttributeGroupIds);
    }

    public List<AssociatedResource> getAssociatedResources() {
        return associatedResources;
    }

    public void setAssociatedResources(List<AssociatedResource> associatedResources) {
        this.associatedResources = associatedResources == null ? new ArrayList<>() : new ArrayList<>(associatedResources);
    }

    public int getAssociatedResourceCount() {
        return associatedResources == null ? 0 : associatedResources.size();
    }

    public String getCreationTime() {
        return creationTime;
    }

    public void setCreationTime(String creationTime) {
        this.creationTime = creationTime;
    }

    public String getLastUpdateTime() {
        return lastUpdateTime;
    }

    public void setLastUpdateTime(String lastUpdateTime) {
        this.lastUpdateTime = lastUpdateTime;
    }

    @RegisterForReflection
    public static class AssociatedResource {
        private String resourceType;
        private String name;
        private String arn;
        private String associationTime;
        private List<String> options = new ArrayList<>();

        public String getResourceType() {
            return resourceType;
        }

        public void setResourceType(String resourceType) {
            this.resourceType = resourceType;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getArn() {
            return arn;
        }

        public void setArn(String arn) {
            this.arn = arn;
        }

        public String getAssociationTime() {
            return associationTime;
        }

        public void setAssociationTime(String associationTime) {
            this.associationTime = associationTime;
        }

        public List<String> getOptions() {
            return options;
        }

        public void setOptions(List<String> options) {
            this.options = options == null ? new ArrayList<>() : new ArrayList<>(options);
        }
    }
}
