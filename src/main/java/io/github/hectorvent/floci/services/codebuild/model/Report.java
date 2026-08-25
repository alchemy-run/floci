package io.github.hectorvent.floci.services.codebuild.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Report {
    public Report() {}

    private String arn;
    private String type;
    private String name;
    private String reportGroupArn;
    private String status;
    private Double created;

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getReportGroupArn() { return reportGroupArn; }
    public void setReportGroupArn(String reportGroupArn) { this.reportGroupArn = reportGroupArn; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Double getCreated() { return created; }
    public void setCreated(Double created) { this.created = created; }
}
