package io.github.hectorvent.floci.services.s3tables.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class Table {
    private String name;
    private String namespace;
    private String tableArn;
    private String versionToken;
    private String warehouseLocation;
    private String format = "ICEBERG";
    private String type = "customer";
    private String createdAt;
    private String createdBy;
    private String modifiedAt;
    private String modifiedBy;
    private String ownerAccountId;
    private String metadataLocation;
    private Object metadata;

    public Table() {}

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getNamespace() { return namespace; }
    public void setNamespace(String namespace) { this.namespace = namespace; }

    public String getTableArn() { return tableArn; }
    public void setTableArn(String tableArn) { this.tableArn = tableArn; }

    public String getVersionToken() { return versionToken; }
    public void setVersionToken(String versionToken) { this.versionToken = versionToken; }

    public String getWarehouseLocation() { return warehouseLocation; }
    public void setWarehouseLocation(String warehouseLocation) { this.warehouseLocation = warehouseLocation; }

    public String getFormat() { return format; }
    public void setFormat(String format) { this.format = format; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public String getModifiedAt() { return modifiedAt; }
    public void setModifiedAt(String modifiedAt) { this.modifiedAt = modifiedAt; }

    public String getModifiedBy() { return modifiedBy; }
    public void setModifiedBy(String modifiedBy) { this.modifiedBy = modifiedBy; }

    public String getOwnerAccountId() { return ownerAccountId; }
    public void setOwnerAccountId(String ownerAccountId) { this.ownerAccountId = ownerAccountId; }

    public String getMetadataLocation() { return metadataLocation; }
    public void setMetadataLocation(String metadataLocation) { this.metadataLocation = metadataLocation; }

    public Object getMetadata() { return metadata; }
    public void setMetadata(Object metadata) { this.metadata = metadata; }
}
