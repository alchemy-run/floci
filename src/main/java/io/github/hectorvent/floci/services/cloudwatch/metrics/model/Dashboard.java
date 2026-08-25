package io.github.hectorvent.floci.services.cloudwatch.metrics.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class Dashboard {
    private String dashboardName;
    private String dashboardArn;
    private String dashboardBody;
    private long lastModified;
    private long size;

    public String getDashboardName() { return dashboardName; }
    public void setDashboardName(String dashboardName) { this.dashboardName = dashboardName; }

    public String getDashboardArn() { return dashboardArn; }
    public void setDashboardArn(String dashboardArn) { this.dashboardArn = dashboardArn; }

    public String getDashboardBody() { return dashboardBody; }
    public void setDashboardBody(String dashboardBody) { this.dashboardBody = dashboardBody; }

    public long getLastModified() { return lastModified; }
    public void setLastModified(long lastModified) { this.lastModified = lastModified; }

    public long getSize() { return size; }
    public void setSize(long size) { this.size = size; }
}
