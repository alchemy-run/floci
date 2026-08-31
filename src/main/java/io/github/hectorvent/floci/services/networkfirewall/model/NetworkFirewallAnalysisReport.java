package io.github.hectorvent.floci.services.networkfirewall.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class NetworkFirewallAnalysisReport {

    private String analysisReportId;
    private String firewallArn;
    private String analysisType;
    private String status;
    private long reportTime;

    public NetworkFirewallAnalysisReport() {
    }

    public String getAnalysisReportId() {
        return analysisReportId;
    }

    public void setAnalysisReportId(String analysisReportId) {
        this.analysisReportId = analysisReportId;
    }

    public String getFirewallArn() {
        return firewallArn;
    }

    public void setFirewallArn(String firewallArn) {
        this.firewallArn = firewallArn;
    }

    public String getAnalysisType() {
        return analysisType;
    }

    public void setAnalysisType(String analysisType) {
        this.analysisType = analysisType;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public long getReportTime() {
        return reportTime;
    }

    public void setReportTime(long reportTime) {
        this.reportTime = reportTime;
    }
}
