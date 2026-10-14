package io.github.hectorvent.floci.services.inspector2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The accounts and EC2 instance resource tags a CIS scan configuration targets. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CisTargets {
    private List<String> accountIds = new ArrayList<>();
    private Map<String, List<String>> targetResourceTags = new LinkedHashMap<>();

    public CisTargets() {
    }

    public CisTargets(List<String> accountIds, Map<String, List<String>> targetResourceTags) {
        setAccountIds(accountIds);
        setTargetResourceTags(targetResourceTags);
    }

    public List<String> getAccountIds() { return accountIds; }

    public void setAccountIds(List<String> accountIds) {
        this.accountIds = accountIds == null ? new ArrayList<>() : new ArrayList<>(accountIds);
    }

    public Map<String, List<String>> getTargetResourceTags() { return targetResourceTags; }

    public void setTargetResourceTags(Map<String, List<String>> targetResourceTags) {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        if (targetResourceTags != null) {
            targetResourceTags.forEach((key, values) -> copy.put(key, new ArrayList<>(values)));
        }
        this.targetResourceTags = copy;
    }

    public CisTargets copy() {
        return new CisTargets(accountIds, targetResourceTags);
    }
}
