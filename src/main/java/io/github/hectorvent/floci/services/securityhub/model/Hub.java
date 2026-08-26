package io.github.hectorvent.floci.services.securityhub.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Security Hub account/region singleton. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Hub {

    private String accountId;
    private String region;
    private String hubArn;
    private String subscribedAt;
    private boolean autoEnableControls = true;
    private String controlFindingGenerator = "SECURITY_CONTROL";
    private Map<String, String> tags = new LinkedHashMap<>();
    private Map<String, Map<String, Object>> findings = new LinkedHashMap<>();
    private Map<String, List<Map<String, Object>>> history = new LinkedHashMap<>();
    private List<Map<String, Object>> standardsSubscriptions = new ArrayList<>();
    private List<String> productSubscriptions = new ArrayList<>();

    public Hub() {
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getHubArn() {
        return hubArn;
    }

    public void setHubArn(String hubArn) {
        this.hubArn = hubArn;
    }

    public String getSubscribedAt() {
        return subscribedAt;
    }

    public void setSubscribedAt(String subscribedAt) {
        this.subscribedAt = subscribedAt;
    }

    public boolean isAutoEnableControls() {
        return autoEnableControls;
    }

    public void setAutoEnableControls(boolean autoEnableControls) {
        this.autoEnableControls = autoEnableControls;
    }

    public String getControlFindingGenerator() {
        return controlFindingGenerator;
    }

    public void setControlFindingGenerator(String controlFindingGenerator) {
        this.controlFindingGenerator = controlFindingGenerator;
    }

    public Map<String, String> getTags() {
        if (tags == null) {
            tags = new LinkedHashMap<>();
        }
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }

    public Map<String, Map<String, Object>> getFindings() {
        if (findings == null) {
            findings = new LinkedHashMap<>();
        }
        return findings;
    }

    public void setFindings(Map<String, Map<String, Object>> findings) {
        this.findings = findings == null ? new LinkedHashMap<>() : new LinkedHashMap<>(findings);
    }

    public Map<String, List<Map<String, Object>>> getHistory() {
        if (history == null) {
            history = new LinkedHashMap<>();
        }
        return history;
    }

    public void setHistory(Map<String, List<Map<String, Object>>> history) {
        this.history = history == null ? new LinkedHashMap<>() : new LinkedHashMap<>(history);
    }

    public List<Map<String, Object>> getStandardsSubscriptions() {
        if (standardsSubscriptions == null) {
            standardsSubscriptions = new ArrayList<>();
        }
        return standardsSubscriptions;
    }

    public void setStandardsSubscriptions(List<Map<String, Object>> standardsSubscriptions) {
        this.standardsSubscriptions =
                standardsSubscriptions == null ? new ArrayList<>() : new ArrayList<>(standardsSubscriptions);
    }

    public List<String> getProductSubscriptions() {
        if (productSubscriptions == null) {
            productSubscriptions = new ArrayList<>();
        }
        return productSubscriptions;
    }

    public void setProductSubscriptions(List<String> productSubscriptions) {
        this.productSubscriptions =
                productSubscriptions == null ? new ArrayList<>() : new ArrayList<>(productSubscriptions);
    }
}
