package io.github.hectorvent.floci.services.bedrockagentcore.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * A code interpreter or browser session. Each READY session is backed by one running container;
 * {@code containerId} names it and {@code serviceHost}/{@code servicePort} are where Floci reaches
 * the browser's DevTools endpoint.
 */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ToolSession {
    private String identifier;
    private String sessionId;
    private String region;
    private String name;
    private String status;
    private long createdAtMillis;
    private long lastUpdatedAtMillis;
    private int sessionTimeoutSeconds;
    private String containerId;
    private String serviceHost;
    private Integer servicePort;
    private String streamEndpoint;
    private String automationStreamStatus;
    private Integer viewPortWidth;
    private Integer viewPortHeight;
    private String profileIdentifier;
    private String clientToken;

    public ToolSession() {}

    public String getIdentifier() { return identifier; }
    public void setIdentifier(String identifier) { this.identifier = identifier; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public long getCreatedAtMillis() { return createdAtMillis; }
    public void setCreatedAtMillis(long createdAtMillis) { this.createdAtMillis = createdAtMillis; }
    public long getLastUpdatedAtMillis() { return lastUpdatedAtMillis; }
    public void setLastUpdatedAtMillis(long lastUpdatedAtMillis) { this.lastUpdatedAtMillis = lastUpdatedAtMillis; }
    public int getSessionTimeoutSeconds() { return sessionTimeoutSeconds; }
    public void setSessionTimeoutSeconds(int sessionTimeoutSeconds) { this.sessionTimeoutSeconds = sessionTimeoutSeconds; }
    public String getContainerId() { return containerId; }
    public void setContainerId(String containerId) { this.containerId = containerId; }
    public String getServiceHost() { return serviceHost; }
    public void setServiceHost(String serviceHost) { this.serviceHost = serviceHost; }
    public Integer getServicePort() { return servicePort; }
    public void setServicePort(Integer servicePort) { this.servicePort = servicePort; }
    public String getStreamEndpoint() { return streamEndpoint; }
    public void setStreamEndpoint(String streamEndpoint) { this.streamEndpoint = streamEndpoint; }
    public String getAutomationStreamStatus() { return automationStreamStatus; }
    public void setAutomationStreamStatus(String automationStreamStatus) {
        this.automationStreamStatus = automationStreamStatus;
    }
    public Integer getViewPortWidth() { return viewPortWidth; }
    public void setViewPortWidth(Integer viewPortWidth) { this.viewPortWidth = viewPortWidth; }
    public Integer getViewPortHeight() { return viewPortHeight; }
    public void setViewPortHeight(Integer viewPortHeight) { this.viewPortHeight = viewPortHeight; }
    public String getProfileIdentifier() { return profileIdentifier; }
    public void setProfileIdentifier(String profileIdentifier) { this.profileIdentifier = profileIdentifier; }
    public String getClientToken() { return clientToken; }
    public void setClientToken(String clientToken) { this.clientToken = clientToken; }
}
