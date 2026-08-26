package io.github.hectorvent.floci.services.vpclattice.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/** A registered VPC Lattice target. */
@RegisterForReflection
public class LatticeTarget {

    private String id;
    private Integer port;
    private String status = "HEALTHY";

    public LatticeTarget() {
    }

    public LatticeTarget(String id, Integer port, String status) {
        this.id = id;
        this.port = port;
        this.status = status;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Integer getPort() {
        return port;
    }

    public void setPort(Integer port) {
        this.port = port;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String key() {
        return (id == null ? "" : id) + "#" + (port == null ? "" : port);
    }
}
