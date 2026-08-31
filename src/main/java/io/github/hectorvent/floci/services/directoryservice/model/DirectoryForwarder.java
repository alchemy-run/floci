package io.github.hectorvent.floci.services.directoryservice.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class DirectoryForwarder {

    private String remoteDomainName;
    private String replicationScope;
    private List<String> dnsIpAddrs = new ArrayList<>();

    public DirectoryForwarder() {
    }

    public String getRemoteDomainName() {
        return remoteDomainName;
    }

    public void setRemoteDomainName(String remoteDomainName) {
        this.remoteDomainName = remoteDomainName;
    }

    public String getReplicationScope() {
        return replicationScope;
    }

    public void setReplicationScope(String replicationScope) {
        this.replicationScope = replicationScope;
    }

    public List<String> getDnsIpAddrs() {
        return dnsIpAddrs;
    }

    public void setDnsIpAddrs(List<String> dnsIpAddrs) {
        this.dnsIpAddrs = dnsIpAddrs != null ? dnsIpAddrs : new ArrayList<>();
    }
}
