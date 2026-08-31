package io.github.hectorvent.floci.services.s3.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * S3 Storage Lens dashboard configuration (S3 Control Put/Get/DeleteStorageLensConfiguration).
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class S3StorageLensConfiguration {

    private String configId;
    private String accountId;
    private String region;
    private String arn;
    private boolean enabled = true;
    private String accountLevelXml;
    private String includeXml;
    private String excludeXml;
    private String dataExportXml;
    private String expandedPrefixesXml;
    private String awsOrgXml;
    private String prefixDelimiter;
    private Map<String, String> tags = new LinkedHashMap<>();

    public S3StorageLensConfiguration() {}

    public String getConfigId() { return configId; }
    public void setConfigId(String configId) { this.configId = configId; }

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getAccountLevelXml() { return accountLevelXml; }
    public void setAccountLevelXml(String accountLevelXml) { this.accountLevelXml = accountLevelXml; }

    public String getIncludeXml() { return includeXml; }
    public void setIncludeXml(String includeXml) { this.includeXml = includeXml; }

    public String getExcludeXml() { return excludeXml; }
    public void setExcludeXml(String excludeXml) { this.excludeXml = excludeXml; }

    public String getDataExportXml() { return dataExportXml; }
    public void setDataExportXml(String dataExportXml) { this.dataExportXml = dataExportXml; }

    public String getExpandedPrefixesXml() { return expandedPrefixesXml; }
    public void setExpandedPrefixesXml(String expandedPrefixesXml) {
        this.expandedPrefixesXml = expandedPrefixesXml;
    }

    public String getAwsOrgXml() { return awsOrgXml; }
    public void setAwsOrgXml(String awsOrgXml) { this.awsOrgXml = awsOrgXml; }

    public String getPrefixDelimiter() { return prefixDelimiter; }
    public void setPrefixDelimiter(String prefixDelimiter) { this.prefixDelimiter = prefixDelimiter; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? tags : new LinkedHashMap<>();
    }
}
