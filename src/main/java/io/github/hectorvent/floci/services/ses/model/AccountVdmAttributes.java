package io.github.hectorvent.floci.services.ses.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Account-level Virtual Deliverability Manager attributes, returned by
 * {@code GetAccount} and written by {@code PutAccountVdmAttributes}.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AccountVdmAttributes {

    @JsonProperty("VdmEnabled")
    private String vdmEnabled = "DISABLED";

    @JsonProperty("EngagementMetrics")
    private String engagementMetrics;

    @JsonProperty("OptimizedSharedDelivery")
    private String optimizedSharedDelivery;

    public AccountVdmAttributes() {}

    public String getVdmEnabled() { return vdmEnabled; }
    public void setVdmEnabled(String vdmEnabled) { this.vdmEnabled = vdmEnabled; }

    public String getEngagementMetrics() { return engagementMetrics; }
    public void setEngagementMetrics(String engagementMetrics) {
        this.engagementMetrics = engagementMetrics;
    }

    public String getOptimizedSharedDelivery() { return optimizedSharedDelivery; }
    public void setOptimizedSharedDelivery(String optimizedSharedDelivery) {
        this.optimizedSharedDelivery = optimizedSharedDelivery;
    }
}
