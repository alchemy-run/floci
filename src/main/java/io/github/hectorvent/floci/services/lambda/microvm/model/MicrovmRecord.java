package io.github.hectorvent.floci.services.lambda.microvm.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * A running (or terminated) MicroVM. Backed by a local Docker container;
 * states mirror the distilled {@code MicrovmState} enum:
 * PENDING/RUNNING/SUSPENDING/SUSPENDED/TERMINATING/TERMINATED.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class MicrovmRecord {

    private String region;
    private String accountId;
    private String microvmId;
    private String state = "PENDING";
    /** Bare hostname (no scheme), e.g. {@code mvm-abc.lambda-microvm.us-east-1.localhost.floci.io}. */
    private String endpoint;
    private String imageArn;
    private String imageVersion;
    private String executionRoleArn;
    private Map<String, Object> idlePolicy;
    private int maximumDurationInSeconds = 3600;
    private long startedAt;
    private Long terminatedAt;
    private String stateReason;
    private List<String> ingressNetworkConnectors;
    private List<String> egressNetworkConnectors;

    /** The backing Docker container (null once terminated). */
    private String containerId;
    /** The in-VM HTTP port the endpoint proxy forwards to. */
    private int port = 8080;

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }

    public String getMicrovmId() { return microvmId; }
    public void setMicrovmId(String microvmId) { this.microvmId = microvmId; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }

    public String getImageArn() { return imageArn; }
    public void setImageArn(String imageArn) { this.imageArn = imageArn; }

    public String getImageVersion() { return imageVersion; }
    public void setImageVersion(String imageVersion) { this.imageVersion = imageVersion; }

    public String getExecutionRoleArn() { return executionRoleArn; }
    public void setExecutionRoleArn(String executionRoleArn) { this.executionRoleArn = executionRoleArn; }

    public Map<String, Object> getIdlePolicy() { return idlePolicy; }
    public void setIdlePolicy(Map<String, Object> idlePolicy) { this.idlePolicy = idlePolicy; }

    public int getMaximumDurationInSeconds() { return maximumDurationInSeconds; }
    public void setMaximumDurationInSeconds(int maximumDurationInSeconds) { this.maximumDurationInSeconds = maximumDurationInSeconds; }

    public long getStartedAt() { return startedAt; }
    public void setStartedAt(long startedAt) { this.startedAt = startedAt; }

    public Long getTerminatedAt() { return terminatedAt; }
    public void setTerminatedAt(Long terminatedAt) { this.terminatedAt = terminatedAt; }

    public String getStateReason() { return stateReason; }
    public void setStateReason(String stateReason) { this.stateReason = stateReason; }

    public List<String> getIngressNetworkConnectors() { return ingressNetworkConnectors; }
    public void setIngressNetworkConnectors(List<String> v) { this.ingressNetworkConnectors = v; }

    public List<String> getEgressNetworkConnectors() { return egressNetworkConnectors; }
    public void setEgressNetworkConnectors(List<String> v) { this.egressNetworkConnectors = v; }

    public String getContainerId() { return containerId; }
    public void setContainerId(String containerId) { this.containerId = containerId; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
}
