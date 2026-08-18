package io.github.hectorvent.floci.services.ses.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A SES v2 tenant — a named isolation container for identities, configuration
 * sets, and templates.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Tenant {

    @JsonProperty("TenantName")
    private String tenantName;

    @JsonProperty("TenantId")
    private String tenantId;

    @JsonProperty("TenantArn")
    private String tenantArn;

    @JsonProperty("CreatedTimestamp")
    private Instant createdTimestamp;

    @JsonProperty("SendingStatus")
    private String sendingStatus = "ENABLED";

    @JsonProperty("SuppressedReasons")
    private List<String> suppressedReasons;

    @JsonProperty("SuppressionScope")
    private String suppressionScope;

    @JsonProperty("Tags")
    private List<Tag> tags = new ArrayList<>();

    public Tenant() {}

    public String getTenantName() { return tenantName; }
    public void setTenantName(String tenantName) { this.tenantName = tenantName; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getTenantArn() { return tenantArn; }
    public void setTenantArn(String tenantArn) { this.tenantArn = tenantArn; }

    public Instant getCreatedTimestamp() { return createdTimestamp; }
    public void setCreatedTimestamp(Instant createdTimestamp) {
        this.createdTimestamp = createdTimestamp;
    }

    public String getSendingStatus() { return sendingStatus; }
    public void setSendingStatus(String sendingStatus) { this.sendingStatus = sendingStatus; }

    public List<String> getSuppressedReasons() { return suppressedReasons; }
    public void setSuppressedReasons(List<String> suppressedReasons) {
        this.suppressedReasons = suppressedReasons;
    }

    public String getSuppressionScope() { return suppressionScope; }
    public void setSuppressionScope(String suppressionScope) { this.suppressionScope = suppressionScope; }

    public List<Tag> getTags() { return tags; }
    public void setTags(List<Tag> tags) {
        this.tags = tags == null ? new ArrayList<>() : tags;
    }
}
