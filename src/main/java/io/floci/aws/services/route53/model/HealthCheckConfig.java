package io.floci.aws.services.route53.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@RegisterForReflection
public class HealthCheckConfig {

    private String type;
    private String ipAddress;
    private Integer port;
    private String resourcePath;
    private String fullyQualifiedDomainName;
    private String searchString;
    private Integer requestInterval;
    private Integer failureThreshold;
    private Boolean measureLatency;
    private Boolean inverted;
    private Boolean disabled;
    private Integer healthThreshold;
    private List<String> childHealthChecks;
    private Boolean enableSni;
    private List<String> regions;
    private String alarmName;
    private String alarmRegion;
    private String insufficientDataHealthStatus;
    private String routingControlArn;

    public HealthCheckConfig() {}

    /**
     * Applies an UpdateHealthCheck request: the members it names replace the current ones, the
     * ones it omits are kept, and {@code resetElements} (FullyQualifiedDomainName, Regions,
     * ResourcePath, ChildHealthChecks) go back to their defaults. Type, RequestInterval,
     * MeasureLatency and RoutingControlArn cannot change after creation, so the update never
     * carries them.
     */
    public void applyUpdate(HealthCheckConfig update, Set<String> resetElements) {
        if (update.ipAddress != null) { ipAddress = update.ipAddress; }
        if (update.port != null) { port = update.port; }
        if (update.resourcePath != null) { resourcePath = update.resourcePath; }
        if (update.fullyQualifiedDomainName != null) { fullyQualifiedDomainName = update.fullyQualifiedDomainName; }
        if (update.searchString != null) { searchString = update.searchString; }
        if (update.failureThreshold != null) { failureThreshold = update.failureThreshold; }
        if (update.inverted != null) { inverted = update.inverted; }
        if (update.disabled != null) { disabled = update.disabled; }
        if (update.healthThreshold != null) { healthThreshold = update.healthThreshold; }
        if (update.childHealthChecks != null) { childHealthChecks = new ArrayList<>(update.childHealthChecks); }
        if (update.enableSni != null) { enableSni = update.enableSni; }
        if (update.regions != null) { regions = new ArrayList<>(update.regions); }
        if (update.alarmName != null) {
            alarmName = update.alarmName;
            alarmRegion = update.alarmRegion;
        }
        if (update.insufficientDataHealthStatus != null) {
            insufficientDataHealthStatus = update.insufficientDataHealthStatus;
        }
        if (resetElements.contains("FullyQualifiedDomainName")) { fullyQualifiedDomainName = null; }
        if (resetElements.contains("Regions")) { regions = null; }
        if (resetElements.contains("ResourcePath")) { resourcePath = null; }
        if (resetElements.contains("ChildHealthChecks")) { childHealthChecks = null; }
    }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }

    public Integer getPort() { return port; }
    public void setPort(Integer port) { this.port = port; }

    public String getResourcePath() { return resourcePath; }
    public void setResourcePath(String resourcePath) { this.resourcePath = resourcePath; }

    public String getFullyQualifiedDomainName() { return fullyQualifiedDomainName; }
    public void setFullyQualifiedDomainName(String fullyQualifiedDomainName) {
        this.fullyQualifiedDomainName = fullyQualifiedDomainName;
    }

    public String getSearchString() { return searchString; }
    public void setSearchString(String searchString) { this.searchString = searchString; }

    public Integer getRequestInterval() { return requestInterval; }
    public void setRequestInterval(Integer requestInterval) { this.requestInterval = requestInterval; }

    public Integer getFailureThreshold() { return failureThreshold; }
    public void setFailureThreshold(Integer failureThreshold) { this.failureThreshold = failureThreshold; }

    public Boolean getMeasureLatency() { return measureLatency; }
    public void setMeasureLatency(Boolean measureLatency) { this.measureLatency = measureLatency; }

    public Boolean getInverted() { return inverted; }
    public void setInverted(Boolean inverted) { this.inverted = inverted; }

    public Boolean getDisabled() { return disabled; }
    public void setDisabled(Boolean disabled) { this.disabled = disabled; }

    public Integer getHealthThreshold() { return healthThreshold; }
    public void setHealthThreshold(Integer healthThreshold) { this.healthThreshold = healthThreshold; }

    public List<String> getChildHealthChecks() { return childHealthChecks; }
    public void setChildHealthChecks(List<String> childHealthChecks) { this.childHealthChecks = childHealthChecks; }

    public Boolean getEnableSni() { return enableSni; }
    public void setEnableSni(Boolean enableSni) { this.enableSni = enableSni; }

    public List<String> getRegions() { return regions; }
    public void setRegions(List<String> regions) { this.regions = regions; }

    public String getAlarmName() { return alarmName; }
    public void setAlarmName(String alarmName) { this.alarmName = alarmName; }

    public String getAlarmRegion() { return alarmRegion; }
    public void setAlarmRegion(String alarmRegion) { this.alarmRegion = alarmRegion; }

    public String getInsufficientDataHealthStatus() { return insufficientDataHealthStatus; }
    public void setInsufficientDataHealthStatus(String insufficientDataHealthStatus) {
        this.insufficientDataHealthStatus = insufficientDataHealthStatus;
    }

    public String getRoutingControlArn() { return routingControlArn; }
    public void setRoutingControlArn(String routingControlArn) { this.routingControlArn = routingControlArn; }
}
