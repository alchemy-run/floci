package io.github.hectorvent.floci.services.dynamodb.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory on-demand backup of a DynamoDB table (schema + items).
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TableBackup {

    private String backupArn;
    private String backupName;
    private String backupStatus;
    private String backupType;
    private long backupCreationDateTime;
    private long backupSizeBytes;
    private String tableName;
    private String tableId;
    private String tableArn;
    private String billingMode;
    private Long readCapacityUnits;
    private Long writeCapacityUnits;
    private List<KeySchemaElement> keySchema = new ArrayList<>();
    private List<AttributeDefinition> attributeDefinitions = new ArrayList<>();
    private Map<String, JsonNode> items = new HashMap<>();

    public String getBackupArn() { return backupArn; }
    public void setBackupArn(String backupArn) { this.backupArn = backupArn; }

    public String getBackupName() { return backupName; }
    public void setBackupName(String backupName) { this.backupName = backupName; }

    public String getBackupStatus() { return backupStatus; }
    public void setBackupStatus(String backupStatus) { this.backupStatus = backupStatus; }

    public String getBackupType() { return backupType; }
    public void setBackupType(String backupType) { this.backupType = backupType; }

    public long getBackupCreationDateTime() { return backupCreationDateTime; }
    public void setBackupCreationDateTime(long backupCreationDateTime) {
        this.backupCreationDateTime = backupCreationDateTime;
    }

    public long getBackupSizeBytes() { return backupSizeBytes; }
    public void setBackupSizeBytes(long backupSizeBytes) { this.backupSizeBytes = backupSizeBytes; }

    public String getTableName() { return tableName; }
    public void setTableName(String tableName) { this.tableName = tableName; }

    public String getTableId() { return tableId; }
    public void setTableId(String tableId) { this.tableId = tableId; }

    public String getTableArn() { return tableArn; }
    public void setTableArn(String tableArn) { this.tableArn = tableArn; }

    public String getBillingMode() { return billingMode; }
    public void setBillingMode(String billingMode) { this.billingMode = billingMode; }

    public Long getReadCapacityUnits() { return readCapacityUnits; }
    public void setReadCapacityUnits(Long readCapacityUnits) { this.readCapacityUnits = readCapacityUnits; }

    public Long getWriteCapacityUnits() { return writeCapacityUnits; }
    public void setWriteCapacityUnits(Long writeCapacityUnits) { this.writeCapacityUnits = writeCapacityUnits; }

    public List<KeySchemaElement> getKeySchema() { return keySchema; }
    public void setKeySchema(List<KeySchemaElement> keySchema) {
        this.keySchema = keySchema != null ? keySchema : new ArrayList<>();
    }

    public List<AttributeDefinition> getAttributeDefinitions() { return attributeDefinitions; }
    public void setAttributeDefinitions(List<AttributeDefinition> attributeDefinitions) {
        this.attributeDefinitions = attributeDefinitions != null ? attributeDefinitions : new ArrayList<>();
    }

    public Map<String, JsonNode> getItems() { return items; }
    public void setItems(Map<String, JsonNode> items) {
        this.items = items != null ? items : new HashMap<>();
    }
}
