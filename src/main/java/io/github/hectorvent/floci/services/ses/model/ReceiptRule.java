package io.github.hectorvent.floci.services.ses.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

/**
 * A SES v1 receipt rule stored on a {@link ReceiptRuleSet}. Floci has no inbound
 * mail endpoint, so rules are stored and returned by the management API but
 * never evaluate incoming mail.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ReceiptRule {

    @JsonProperty("Name")
    private String name;

    @JsonProperty("Enabled")
    private Boolean enabled;

    @JsonProperty("TlsPolicy")
    private String tlsPolicy;

    @JsonProperty("Recipients")
    private List<String> recipients = new ArrayList<>();

    @JsonProperty("Actions")
    private List<ReceiptAction> actions = new ArrayList<>();

    @JsonProperty("ScanEnabled")
    private Boolean scanEnabled;

    public ReceiptRule() {}

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

    public String getTlsPolicy() { return tlsPolicy; }
    public void setTlsPolicy(String tlsPolicy) { this.tlsPolicy = tlsPolicy; }

    public List<String> getRecipients() { return recipients; }
    public void setRecipients(List<String> recipients) {
        this.recipients = recipients == null ? new ArrayList<>() : recipients;
    }

    public List<ReceiptAction> getActions() { return actions; }
    public void setActions(List<ReceiptAction> actions) {
        this.actions = actions == null ? new ArrayList<>() : actions;
    }

    public Boolean getScanEnabled() { return scanEnabled; }
    public void setScanEnabled(Boolean scanEnabled) { this.scanEnabled = scanEnabled; }
}
