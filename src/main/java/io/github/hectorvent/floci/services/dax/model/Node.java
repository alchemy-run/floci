package io.github.hectorvent.floci.services.dax.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class Node {

    private String nodeId;
    private String address;
    private int port;
    private String url;
    private long nodeCreateTime;
    private String availabilityZone;
    private String nodeStatus;
    private String parameterGroupStatus;

    public Node() {}

    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public long getNodeCreateTime() { return nodeCreateTime; }
    public void setNodeCreateTime(long nodeCreateTime) { this.nodeCreateTime = nodeCreateTime; }

    public String getAvailabilityZone() { return availabilityZone; }
    public void setAvailabilityZone(String availabilityZone) { this.availabilityZone = availabilityZone; }

    public String getNodeStatus() { return nodeStatus; }
    public void setNodeStatus(String nodeStatus) { this.nodeStatus = nodeStatus; }

    public String getParameterGroupStatus() { return parameterGroupStatus; }
    public void setParameterGroupStatus(String parameterGroupStatus) {
        this.parameterGroupStatus = parameterGroupStatus;
    }
}
