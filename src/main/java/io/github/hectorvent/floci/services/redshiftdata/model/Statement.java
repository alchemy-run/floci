package io.github.hectorvent.floci.services.redshiftdata.model;

import java.util.ArrayList;
import java.util.List;

/**
 * In-memory Redshift Data API statement (or sub-statement).
 */
public class Statement {

    private String id;
    private String parentId;
    private String sql;
    private String status;
    private String workgroupName;
    private String clusterIdentifier;
    private String database;
    private String dbUser;
    private String secretArn;
    private String resultFormat;
    private String statementName;
    private String error;
    private boolean batch;
    private boolean hasResultSet;
    private long createdAtEpochSeconds;
    private long updatedAtEpochSeconds;
    private long duration;
    private long resultRows;
    private long resultSize;
    private final List<String> subIds = new ArrayList<>();
    private final List<String> columnNames = new ArrayList<>();
    private final List<List<Object>> rows = new ArrayList<>();

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getParentId() {
        return parentId;
    }

    public void setParentId(String parentId) {
        this.parentId = parentId;
    }

    public String getSql() {
        return sql;
    }

    public void setSql(String sql) {
        this.sql = sql;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getWorkgroupName() {
        return workgroupName;
    }

    public void setWorkgroupName(String workgroupName) {
        this.workgroupName = workgroupName;
    }

    public String getClusterIdentifier() {
        return clusterIdentifier;
    }

    public void setClusterIdentifier(String clusterIdentifier) {
        this.clusterIdentifier = clusterIdentifier;
    }

    public String getDatabase() {
        return database;
    }

    public void setDatabase(String database) {
        this.database = database;
    }

    public String getDbUser() {
        return dbUser;
    }

    public void setDbUser(String dbUser) {
        this.dbUser = dbUser;
    }

    public String getSecretArn() {
        return secretArn;
    }

    public void setSecretArn(String secretArn) {
        this.secretArn = secretArn;
    }

    public String getResultFormat() {
        return resultFormat;
    }

    public void setResultFormat(String resultFormat) {
        this.resultFormat = resultFormat;
    }

    public String getStatementName() {
        return statementName;
    }

    public void setStatementName(String statementName) {
        this.statementName = statementName;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public boolean isBatch() {
        return batch;
    }

    public void setBatch(boolean batch) {
        this.batch = batch;
    }

    public boolean isHasResultSet() {
        return hasResultSet;
    }

    public void setHasResultSet(boolean hasResultSet) {
        this.hasResultSet = hasResultSet;
    }

    public long getCreatedAtEpochSeconds() {
        return createdAtEpochSeconds;
    }

    public void setCreatedAtEpochSeconds(long createdAtEpochSeconds) {
        this.createdAtEpochSeconds = createdAtEpochSeconds;
    }

    public long getUpdatedAtEpochSeconds() {
        return updatedAtEpochSeconds;
    }

    public void setUpdatedAtEpochSeconds(long updatedAtEpochSeconds) {
        this.updatedAtEpochSeconds = updatedAtEpochSeconds;
    }

    public long getDuration() {
        return duration;
    }

    public void setDuration(long duration) {
        this.duration = duration;
    }

    public long getResultRows() {
        return resultRows;
    }

    public void setResultRows(long resultRows) {
        this.resultRows = resultRows;
    }

    public long getResultSize() {
        return resultSize;
    }

    public void setResultSize(long resultSize) {
        this.resultSize = resultSize;
    }

    public List<String> getSubIds() {
        return subIds;
    }

    public List<String> getColumnNames() {
        return columnNames;
    }

    public List<List<Object>> getRows() {
        return rows;
    }
}
