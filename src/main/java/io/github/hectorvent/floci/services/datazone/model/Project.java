package io.github.hectorvent.floci.services.datazone.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

/** An Amazon DataZone project. Wire names are camelCase restJson1. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Project {

    private String id;
    private String domainId;
    private String name;
    private String description;
    private String projectStatus;
    private String createdBy;
    private String createdAt;
    private String lastUpdatedAt;
    private String domainUnitId;
    private String region;
    private List<String> glossaryTerms = new ArrayList<>();
    private List<Membership> memberships = new ArrayList<>();

    public Project() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getDomainId() {
        return domainId;
    }

    public void setDomainId(String domainId) {
        this.domainId = domainId;
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

    public String getProjectStatus() {
        return projectStatus;
    }

    public void setProjectStatus(String projectStatus) {
        this.projectStatus = projectStatus;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public String getLastUpdatedAt() {
        return lastUpdatedAt;
    }

    public void setLastUpdatedAt(String lastUpdatedAt) {
        this.lastUpdatedAt = lastUpdatedAt;
    }

    public String getDomainUnitId() {
        return domainUnitId;
    }

    public void setDomainUnitId(String domainUnitId) {
        this.domainUnitId = domainUnitId;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public List<String> getGlossaryTerms() {
        return glossaryTerms;
    }

    public void setGlossaryTerms(List<String> glossaryTerms) {
        this.glossaryTerms = glossaryTerms == null ? new ArrayList<>() : new ArrayList<>(glossaryTerms);
    }

    public List<Membership> getMemberships() {
        return memberships;
    }

    public void setMemberships(List<Membership> memberships) {
        this.memberships = memberships == null ? new ArrayList<>() : new ArrayList<>(memberships);
    }

    @RegisterForReflection
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Membership {
        private String userIdentifier;
        private String groupIdentifier;
        private String designation;

        public Membership() {
        }

        public String getUserIdentifier() {
            return userIdentifier;
        }

        public void setUserIdentifier(String userIdentifier) {
            this.userIdentifier = userIdentifier;
        }

        public String getGroupIdentifier() {
            return groupIdentifier;
        }

        public void setGroupIdentifier(String groupIdentifier) {
            this.groupIdentifier = groupIdentifier;
        }

        public String getDesignation() {
            return designation;
        }

        public void setDesignation(String designation) {
            this.designation = designation;
        }
    }
}
