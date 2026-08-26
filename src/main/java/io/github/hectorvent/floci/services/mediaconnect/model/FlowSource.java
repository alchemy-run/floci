package io.github.hectorvent.floci.services.mediaconnect.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** Ingest source attached to a MediaConnect flow. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FlowSource {

    private String name;
    private String sourceArn;
    private String description;
    private String protocol;
    private String whitelistCidr;
    private Integer ingestPort;
    private String ingestIp;
    private Integer maxBitrate;
    private Integer maxLatency;
    private Integer minLatency;
    private String streamId;
    private String senderIpAddress;
    private Integer senderControlPort;

    public FlowSource() {
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSourceArn() {
        return sourceArn;
    }

    public void setSourceArn(String sourceArn) {
        this.sourceArn = sourceArn;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getProtocol() {
        return protocol;
    }

    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }

    public String getWhitelistCidr() {
        return whitelistCidr;
    }

    public void setWhitelistCidr(String whitelistCidr) {
        this.whitelistCidr = whitelistCidr;
    }

    public Integer getIngestPort() {
        return ingestPort;
    }

    public void setIngestPort(Integer ingestPort) {
        this.ingestPort = ingestPort;
    }

    public String getIngestIp() {
        return ingestIp;
    }

    public void setIngestIp(String ingestIp) {
        this.ingestIp = ingestIp;
    }

    public Integer getMaxBitrate() {
        return maxBitrate;
    }

    public void setMaxBitrate(Integer maxBitrate) {
        this.maxBitrate = maxBitrate;
    }

    public Integer getMaxLatency() {
        return maxLatency;
    }

    public void setMaxLatency(Integer maxLatency) {
        this.maxLatency = maxLatency;
    }

    public Integer getMinLatency() {
        return minLatency;
    }

    public void setMinLatency(Integer minLatency) {
        this.minLatency = minLatency;
    }

    public String getStreamId() {
        return streamId;
    }

    public void setStreamId(String streamId) {
        this.streamId = streamId;
    }

    public String getSenderIpAddress() {
        return senderIpAddress;
    }

    public void setSenderIpAddress(String senderIpAddress) {
        this.senderIpAddress = senderIpAddress;
    }

    public Integer getSenderControlPort() {
        return senderControlPort;
    }

    public void setSenderControlPort(Integer senderControlPort) {
        this.senderControlPort = senderControlPort;
    }
}
