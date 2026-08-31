package io.github.hectorvent.floci.services.securityhub.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** A Security Hub custom insight. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SecurityHubInsight {

    private String accountId;
    private String region;
    private String insightArn;
    private String name;
    private String groupByAttribute;
    private Map<String, Object> filters = new LinkedHashMap<>();

    public SecurityHubInsight() {
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

    public String getInsightArn() {
        return insightArn;
    }

    public void setInsightArn(String insightArn) {
        this.insightArn = insightArn;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getGroupByAttribute() {
        return groupByAttribute;
    }

    public void setGroupByAttribute(String groupByAttribute) {
        this.groupByAttribute = groupByAttribute;
    }

    public Map<String, Object> getFilters() {
        if (filters == null) {
            filters = new LinkedHashMap<>();
        }
        return filters;
    }

    public void setFilters(Map<String, Object> filters) {
        this.filters = filters == null ? new LinkedHashMap<>() : new LinkedHashMap<>(filters);
    }
}
