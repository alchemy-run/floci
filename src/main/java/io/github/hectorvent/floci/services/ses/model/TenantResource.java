package io.github.hectorvent.floci.services.ses.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * An association between a {@link Tenant} and another SES resource
 * (identity, configuration set, or template).
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TenantResource {

    @JsonProperty("TenantName")
    private String tenantName;

    @JsonProperty("ResourceArn")
    private String resourceArn;

    @JsonProperty("ResourceType")
    private String resourceType;

    public TenantResource() {}

    public TenantResource(String tenantName, String resourceArn, String resourceType) {
        this.tenantName = tenantName;
        this.resourceArn = resourceArn;
        this.resourceType = resourceType;
    }

    public String getTenantName() { return tenantName; }
    public void setTenantName(String tenantName) { this.tenantName = tenantName; }

    public String getResourceArn() { return resourceArn; }
    public void setResourceArn(String resourceArn) { this.resourceArn = resourceArn; }

    public String getResourceType() { return resourceType; }
    public void setResourceType(String resourceType) { this.resourceType = resourceType; }
}
