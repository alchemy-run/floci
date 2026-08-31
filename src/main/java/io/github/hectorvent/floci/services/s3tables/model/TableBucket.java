package io.github.hectorvent.floci.services.s3tables.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RegisterForReflection
public class TableBucket {
    private String name;
    private String arn;
    private String ownerAccountId;
    private String createdAt;
    private String tableBucketId;
    private String type = "customer";
    private Object encryptionConfiguration;
    private Map<String, TableNamespace> namespaces = new ConcurrentHashMap<>();
    private Map<String, Table> tables = new ConcurrentHashMap<>();

    public TableBucket() {}

    public TableBucket(String name, String arn, String ownerAccountId, String createdAt, String tableBucketId) {
        this.name = name;
        this.arn = arn;
        this.ownerAccountId = ownerAccountId;
        this.createdAt = createdAt;
        this.tableBucketId = tableBucketId;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public String getOwnerAccountId() { return ownerAccountId; }
    public void setOwnerAccountId(String ownerAccountId) { this.ownerAccountId = ownerAccountId; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public String getTableBucketId() { return tableBucketId; }
    public void setTableBucketId(String tableBucketId) { this.tableBucketId = tableBucketId; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public Object getEncryptionConfiguration() { return encryptionConfiguration; }
    public void setEncryptionConfiguration(Object encryptionConfiguration) {
        this.encryptionConfiguration = encryptionConfiguration;
    }

    public Map<String, TableNamespace> getNamespaces() { return namespaces; }
    public void setNamespaces(Map<String, TableNamespace> namespaces) {
        this.namespaces = namespaces != null ? namespaces : new ConcurrentHashMap<>();
    }

    public Map<String, Table> getTables() { return tables; }
    public void setTables(Map<String, Table> tables) {
        this.tables = tables != null ? tables : new ConcurrentHashMap<>();
    }

    public static String tableKey(String namespace, String name) {
        return namespace + "/" + name;
    }
}
