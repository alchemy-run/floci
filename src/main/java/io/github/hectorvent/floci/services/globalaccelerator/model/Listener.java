package io.github.hectorvent.floci.services.globalaccelerator.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class Listener {

    private String listenerArn;
    private String acceleratorArn;
    private List<PortRange> portRanges = new ArrayList<>();
    private String protocol;
    private String clientAffinity;
    private String idempotencyToken;

    public Listener() {
    }

    public String getListenerArn() {
        return listenerArn;
    }

    public void setListenerArn(String listenerArn) {
        this.listenerArn = listenerArn;
    }

    public String getAcceleratorArn() {
        return acceleratorArn;
    }

    public void setAcceleratorArn(String acceleratorArn) {
        this.acceleratorArn = acceleratorArn;
    }

    public List<PortRange> getPortRanges() {
        return portRanges;
    }

    public void setPortRanges(List<PortRange> portRanges) {
        this.portRanges = portRanges != null ? portRanges : new ArrayList<>();
    }

    public String getProtocol() {
        return protocol;
    }

    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }

    public String getClientAffinity() {
        return clientAffinity;
    }

    public void setClientAffinity(String clientAffinity) {
        this.clientAffinity = clientAffinity;
    }

    public String getIdempotencyToken() {
        return idempotencyToken;
    }

    public void setIdempotencyToken(String idempotencyToken) {
        this.idempotencyToken = idempotencyToken;
    }

    @RegisterForReflection
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PortRange {
        private Integer fromPort;
        private Integer toPort;

        public PortRange() {
        }

        public Integer getFromPort() {
            return fromPort;
        }

        public void setFromPort(Integer fromPort) {
            this.fromPort = fromPort;
        }

        public Integer getToPort() {
            return toPort;
        }

        public void setToPort(Integer toPort) {
            this.toPort = toPort;
        }
    }
}
