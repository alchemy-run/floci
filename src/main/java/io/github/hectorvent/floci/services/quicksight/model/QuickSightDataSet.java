package io.github.hectorvent.floci.services.quicksight.model;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

@RegisterForReflection
public class QuickSightDataSet {

    private String dataSetId;
    private String name;
    private String arn;
    private String region;
    private String accountId;
    private String importMode;
    private long createdTime;
    private long lastUpdatedTime;
    private JsonNode physicalTableMap;
    private JsonNode logicalTableMap;
    private JsonNode columnGroups;
    private JsonNode fieldFolders;
    private JsonNode permissions;
    private JsonNode rowLevelPermissionDataSet;
    private JsonNode dataSetUsageConfiguration;
    private JsonNode datasetParameters;
    private Map<String, String> tags = new LinkedHashMap<>();
    private Map<String, QuickSightIngestion> ingestions = new LinkedHashMap<>();

    public String getDataSetId() {
        return dataSetId;
    }

    public void setDataSetId(String dataSetId) {
        this.dataSetId = dataSetId;
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

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public String getImportMode() {
        return importMode;
    }

    public void setImportMode(String importMode) {
        this.importMode = importMode;
    }

    public long getCreatedTime() {
        return createdTime;
    }

    public void setCreatedTime(long createdTime) {
        this.createdTime = createdTime;
    }

    public long getLastUpdatedTime() {
        return lastUpdatedTime;
    }

    public void setLastUpdatedTime(long lastUpdatedTime) {
        this.lastUpdatedTime = lastUpdatedTime;
    }

    public JsonNode getPhysicalTableMap() {
        return physicalTableMap;
    }

    public void setPhysicalTableMap(JsonNode physicalTableMap) {
        this.physicalTableMap = physicalTableMap == null ? null : physicalTableMap.deepCopy();
    }

    public JsonNode getLogicalTableMap() {
        return logicalTableMap;
    }

    public void setLogicalTableMap(JsonNode logicalTableMap) {
        this.logicalTableMap = logicalTableMap == null ? null : logicalTableMap.deepCopy();
    }

    public JsonNode getColumnGroups() {
        return columnGroups;
    }

    public void setColumnGroups(JsonNode columnGroups) {
        this.columnGroups = columnGroups == null ? null : columnGroups.deepCopy();
    }

    public JsonNode getFieldFolders() {
        return fieldFolders;
    }

    public void setFieldFolders(JsonNode fieldFolders) {
        this.fieldFolders = fieldFolders == null ? null : fieldFolders.deepCopy();
    }

    public JsonNode getPermissions() {
        return permissions;
    }

    public void setPermissions(JsonNode permissions) {
        this.permissions = permissions == null ? null : permissions.deepCopy();
    }

    public JsonNode getRowLevelPermissionDataSet() {
        return rowLevelPermissionDataSet;
    }

    public void setRowLevelPermissionDataSet(JsonNode rowLevelPermissionDataSet) {
        this.rowLevelPermissionDataSet = rowLevelPermissionDataSet == null
                ? null
                : rowLevelPermissionDataSet.deepCopy();
    }

    public JsonNode getDataSetUsageConfiguration() {
        return dataSetUsageConfiguration;
    }

    public void setDataSetUsageConfiguration(JsonNode dataSetUsageConfiguration) {
        this.dataSetUsageConfiguration = dataSetUsageConfiguration == null
                ? null
                : dataSetUsageConfiguration.deepCopy();
    }

    public JsonNode getDatasetParameters() {
        return datasetParameters;
    }

    public void setDatasetParameters(JsonNode datasetParameters) {
        this.datasetParameters = datasetParameters == null ? null : datasetParameters.deepCopy();
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }

    public Map<String, QuickSightIngestion> getIngestions() {
        return ingestions;
    }

    public void setIngestions(Map<String, QuickSightIngestion> ingestions) {
        this.ingestions = ingestions == null ? new LinkedHashMap<>() : new LinkedHashMap<>(ingestions);
    }
}
