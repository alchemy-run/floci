package io.github.hectorvent.floci.services.athena.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class NamedQuery {
    @JsonProperty("NamedQueryId")
    private String namedQueryId;
    @JsonProperty("Name")
    private String name;
    @JsonProperty("Description")
    private String description;
    @JsonProperty("Database")
    private String database;
    @JsonProperty("QueryString")
    private String queryString;
    @JsonProperty("WorkGroup")
    private String workGroup;
    private String clientRequestToken;

    public String getNamedQueryId() { return namedQueryId; }
    public void setNamedQueryId(String namedQueryId) { this.namedQueryId = namedQueryId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getDatabase() { return database; }
    public void setDatabase(String database) { this.database = database; }
    public String getQueryString() { return queryString; }
    public void setQueryString(String queryString) { this.queryString = queryString; }
    public String getWorkGroup() { return workGroup; }
    public void setWorkGroup(String workGroup) { this.workGroup = workGroup; }
    public String getClientRequestToken() { return clientRequestToken; }
    public void setClientRequestToken(String clientRequestToken) { this.clientRequestToken = clientRequestToken; }
}
