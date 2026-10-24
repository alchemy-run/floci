package io.github.hectorvent.floci.services.appconfig.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class HostedConfigurationVersion {
    @JsonProperty("ApplicationId")
    private String applicationId;
    @JsonProperty("ConfigurationProfileId")
    private String configurationProfileId;
    @JsonProperty("VersionNumber")
    private int versionNumber;
    @JsonProperty("Description")
    private String description;
    @JsonProperty("Content")
    private byte[] content;
    @JsonProperty("ContentType")
    private String contentType;
    @JsonProperty("VersionLabel")
    private String versionLabel;

    public HostedConfigurationVersion() {}

    public String getApplicationId() { return applicationId; }
    public void setApplicationId(String applicationId) { this.applicationId = applicationId; }

    public String getConfigurationProfileId() { return configurationProfileId; }
    public void setConfigurationProfileId(String configurationProfileId) { this.configurationProfileId = configurationProfileId; }

    public int getVersionNumber() { return versionNumber; }
    public void setVersionNumber(int versionNumber) { this.versionNumber = versionNumber; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public byte[] getContent() { return content; }
    public void setContent(byte[] content) { this.content = content; }

    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }

    public String getVersionLabel() { return versionLabel; }
    public void setVersionLabel(String versionLabel) { this.versionLabel = versionLabel; }
}
