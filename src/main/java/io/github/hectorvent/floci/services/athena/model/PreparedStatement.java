package io.github.hectorvent.floci.services.athena.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

@RegisterForReflection
public class PreparedStatement {
    @JsonProperty("StatementName")
    private String statementName;
    @JsonProperty("WorkGroupName")
    private String workGroupName;
    @JsonProperty("QueryStatement")
    private String queryStatement;
    @JsonProperty("Description")
    private String description;
    @JsonProperty("LastModifiedTime")
    private Instant lastModifiedTime;

    public String getStatementName() { return statementName; }
    public void setStatementName(String statementName) { this.statementName = statementName; }
    public String getWorkGroupName() { return workGroupName; }
    public void setWorkGroupName(String workGroupName) { this.workGroupName = workGroupName; }
    public String getQueryStatement() { return queryStatement; }
    public void setQueryStatement(String queryStatement) { this.queryStatement = queryStatement; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Instant getLastModifiedTime() { return lastModifiedTime; }
    public void setLastModifiedTime(Instant lastModifiedTime) { this.lastModifiedTime = lastModifiedTime; }
}
