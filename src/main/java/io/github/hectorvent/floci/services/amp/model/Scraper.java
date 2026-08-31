package io.github.hectorvent.floci.services.amp.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** An Amazon Managed Service for Prometheus scraper. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Scraper {

    private String scraperId;
    private String region;
    private String arn;
    private String roleArn;
    private String alias;
    private String statusCode;
    private long createdAt;
    private long lastModifiedAt;
    private Map<String, String> tags;
    private String configurationBlob;
    private JsonNode source;
    private JsonNode destination;
    private JsonNode roleConfiguration;
    private JsonNode loggingDestination;
    private JsonNode scraperComponents;
    private String loggingStatusCode;
    private long loggingModifiedAt;

    public Scraper() {
    }

    public String getScraperId() {
        return scraperId;
    }

    public void setScraperId(String scraperId) {
        this.scraperId = scraperId;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getArn() {
        return arn;
    }

    public void setArn(String arn) {
        this.arn = arn;
    }

    public String getRoleArn() {
        return roleArn;
    }

    public void setRoleArn(String roleArn) {
        this.roleArn = roleArn;
    }

    public String getAlias() {
        return alias;
    }

    public void setAlias(String alias) {
        this.alias = alias;
    }

    public String getStatusCode() {
        return statusCode;
    }

    public void setStatusCode(String statusCode) {
        this.statusCode = statusCode;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getLastModifiedAt() {
        return lastModifiedAt;
    }

    public void setLastModifiedAt(long lastModifiedAt) {
        this.lastModifiedAt = lastModifiedAt;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? null : new LinkedHashMap<>(tags);
    }

    public String getConfigurationBlob() {
        return configurationBlob;
    }

    public void setConfigurationBlob(String configurationBlob) {
        this.configurationBlob = configurationBlob;
    }

    public JsonNode getSource() {
        return source;
    }

    public void setSource(JsonNode source) {
        this.source = source;
    }

    public JsonNode getDestination() {
        return destination;
    }

    public void setDestination(JsonNode destination) {
        this.destination = destination;
    }

    public JsonNode getRoleConfiguration() {
        return roleConfiguration;
    }

    public void setRoleConfiguration(JsonNode roleConfiguration) {
        this.roleConfiguration = roleConfiguration;
    }

    public JsonNode getLoggingDestination() {
        return loggingDestination;
    }

    public void setLoggingDestination(JsonNode loggingDestination) {
        this.loggingDestination = loggingDestination;
    }

    public JsonNode getScraperComponents() {
        return scraperComponents;
    }

    public void setScraperComponents(JsonNode scraperComponents) {
        this.scraperComponents = scraperComponents;
    }

    public String getLoggingStatusCode() {
        return loggingStatusCode;
    }

    public void setLoggingStatusCode(String loggingStatusCode) {
        this.loggingStatusCode = loggingStatusCode;
    }

    public long getLoggingModifiedAt() {
        return loggingModifiedAt;
    }

    public void setLoggingModifiedAt(long loggingModifiedAt) {
        this.loggingModifiedAt = loggingModifiedAt;
    }

    public boolean hasLoggingConfiguration() {
        return loggingDestination != null;
    }
}
