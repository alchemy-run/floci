package io.github.hectorvent.floci.services.s3tables.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class TableNamespace {
    private String name;
    private String namespaceId;
    private String createdAt;
    private String createdBy;
    private String ownerAccountId;

    public TableNamespace() {}

    public TableNamespace(String name, String namespaceId, String createdAt, String createdBy, String ownerAccountId) {
        this.name = name;
        this.namespaceId = namespaceId;
        this.createdAt = createdAt;
        this.createdBy = createdBy;
        this.ownerAccountId = ownerAccountId;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getNamespaceId() { return namespaceId; }
    public void setNamespaceId(String namespaceId) { this.namespaceId = namespaceId; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public String getOwnerAccountId() { return ownerAccountId; }
    public void setOwnerAccountId(String ownerAccountId) { this.ownerAccountId = ownerAccountId; }
}
