package io.github.hectorvent.floci.services.ses.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * A SES v1 receipt IP-address filter (allow/block a CIDR). Stored inertly —
 * Floci has no inbound mail endpoint.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ReceiptFilter {

    @JsonProperty("Name")
    private String name;

    @JsonProperty("Policy")
    private String policy;

    @JsonProperty("Cidr")
    private String cidr;

    public ReceiptFilter() {}

    public ReceiptFilter(String name, String policy, String cidr) {
        this.name = name;
        this.policy = policy;
        this.cidr = cidr;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getPolicy() { return policy; }
    public void setPolicy(String policy) { this.policy = policy; }

    public String getCidr() { return cidr; }
    public void setCidr(String cidr) { this.cidr = cidr; }
}
