package io.github.hectorvent.floci.services.securityhub.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

/** Cross-region finding aggregator. One per account. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SecurityHubFindingAggregator {

    private String accountId;
    private String region;
    private String findingAggregatorArn;
    private String findingAggregationRegion;
    private String regionLinkingMode;
    private List<String> regions = new ArrayList<>();

    public SecurityHubFindingAggregator() {
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

    public String getFindingAggregatorArn() {
        return findingAggregatorArn;
    }

    public void setFindingAggregatorArn(String findingAggregatorArn) {
        this.findingAggregatorArn = findingAggregatorArn;
    }

    public String getFindingAggregationRegion() {
        return findingAggregationRegion;
    }

    public void setFindingAggregationRegion(String findingAggregationRegion) {
        this.findingAggregationRegion = findingAggregationRegion;
    }

    public String getRegionLinkingMode() {
        return regionLinkingMode;
    }

    public void setRegionLinkingMode(String regionLinkingMode) {
        this.regionLinkingMode = regionLinkingMode;
    }

    public List<String> getRegions() {
        if (regions == null) {
            regions = new ArrayList<>();
        }
        return regions;
    }

    public void setRegions(List<String> regions) {
        this.regions = regions == null ? new ArrayList<>() : new ArrayList<>(regions);
    }
}
