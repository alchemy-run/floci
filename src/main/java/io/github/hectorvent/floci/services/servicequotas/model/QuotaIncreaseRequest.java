package io.github.hectorvent.floci.services.servicequotas.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * A {@code RequestServiceQuotaIncrease} record in the 90-day change history.
 */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class QuotaIncreaseRequest {

    private String id;
    private String serviceCode;
    private String serviceName;
    private String quotaCode;
    private String quotaName;
    private String quotaArn;
    private Double desiredValue;
    private String status;
    private Long created;
    private Long lastUpdated;
    private String unit;
    private Boolean globalQuota;
    private String contextId;
    private String requester;

    public QuotaIncreaseRequest() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getServiceCode() {
        return serviceCode;
    }

    public void setServiceCode(String serviceCode) {
        this.serviceCode = serviceCode;
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public String getQuotaCode() {
        return quotaCode;
    }

    public void setQuotaCode(String quotaCode) {
        this.quotaCode = quotaCode;
    }

    public String getQuotaName() {
        return quotaName;
    }

    public void setQuotaName(String quotaName) {
        this.quotaName = quotaName;
    }

    public String getQuotaArn() {
        return quotaArn;
    }

    public void setQuotaArn(String quotaArn) {
        this.quotaArn = quotaArn;
    }

    public Double getDesiredValue() {
        return desiredValue;
    }

    public void setDesiredValue(Double desiredValue) {
        this.desiredValue = desiredValue;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getCreated() {
        return created;
    }

    public void setCreated(Long created) {
        this.created = created;
    }

    public Long getLastUpdated() {
        return lastUpdated;
    }

    public void setLastUpdated(Long lastUpdated) {
        this.lastUpdated = lastUpdated;
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    public Boolean getGlobalQuota() {
        return globalQuota;
    }

    public void setGlobalQuota(Boolean globalQuota) {
        this.globalQuota = globalQuota;
    }

    public String getContextId() {
        return contextId;
    }

    public void setContextId(String contextId) {
        this.contextId = contextId;
    }

    public String getRequester() {
        return requester;
    }

    public void setRequester(String requester) {
        this.requester = requester;
    }
}
