package io.github.hectorvent.floci.services.ssmincidents.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

/** Incident record. ARNs omit region. */
@RegisterForReflection
public class IncidentRecord {

    private String arn;
    private String title;
    private String summary;
    private String status;
    private int impact;
    private long creationTime;
    private Long resolvedTime;
    private long lastModifiedTime;
    private String lastModifiedBy;
    private String createdBy;
    private String source;
    private String dedupeString;
    private List<RelatedItem> relatedItems = new ArrayList<>();

    public IncidentRecord() {
    }

    public String getArn() {
        return arn;
    }

    public void setArn(String arn) {
        this.arn = arn;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getImpact() {
        return impact;
    }

    public void setImpact(int impact) {
        this.impact = impact;
    }

    public long getCreationTime() {
        return creationTime;
    }

    public void setCreationTime(long creationTime) {
        this.creationTime = creationTime;
    }

    public Long getResolvedTime() {
        return resolvedTime;
    }

    public void setResolvedTime(Long resolvedTime) {
        this.resolvedTime = resolvedTime;
    }

    public long getLastModifiedTime() {
        return lastModifiedTime;
    }

    public void setLastModifiedTime(long lastModifiedTime) {
        this.lastModifiedTime = lastModifiedTime;
    }

    public String getLastModifiedBy() {
        return lastModifiedBy;
    }

    public void setLastModifiedBy(String lastModifiedBy) {
        this.lastModifiedBy = lastModifiedBy;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getDedupeString() {
        return dedupeString;
    }

    public void setDedupeString(String dedupeString) {
        this.dedupeString = dedupeString;
    }

    public List<RelatedItem> getRelatedItems() {
        return relatedItems;
    }

    public void setRelatedItems(List<RelatedItem> relatedItems) {
        this.relatedItems = relatedItems == null ? new ArrayList<>() : new ArrayList<>(relatedItems);
    }
}
