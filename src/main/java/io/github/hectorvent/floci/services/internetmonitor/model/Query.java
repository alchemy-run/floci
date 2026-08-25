package io.github.hectorvent.floci.services.internetmonitor.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/** An Internet Monitor query. Queries complete immediately in the emulator. */
@RegisterForReflection
public class Query {

    private String queryId;
    private String monitorName;
    private String status;
    private String queryType;

    public Query() {
    }

    public Query(String queryId, String monitorName, String status, String queryType) {
        this.queryId = queryId;
        this.monitorName = monitorName;
        this.status = status;
        this.queryType = queryType;
    }

    public String getQueryId() {
        return queryId;
    }

    public void setQueryId(String queryId) {
        this.queryId = queryId;
    }

    public String getMonitorName() {
        return monitorName;
    }

    public void setMonitorName(String monitorName) {
        this.monitorName = monitorName;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getQueryType() {
        return queryType;
    }

    public void setQueryType(String queryType) {
        this.queryType = queryType;
    }
}
