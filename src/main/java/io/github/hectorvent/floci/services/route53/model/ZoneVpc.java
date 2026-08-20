package io.github.hectorvent.floci.services.route53.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Objects;

@RegisterForReflection
public class ZoneVpc {

    private String vpcId;
    private String vpcRegion;

    public ZoneVpc() {}

    public ZoneVpc(String vpcId, String vpcRegion) {
        this.vpcId = vpcId;
        this.vpcRegion = vpcRegion;
    }

    public String getVpcId() { return vpcId; }
    public void setVpcId(String vpcId) { this.vpcId = vpcId; }

    public String getVpcRegion() { return vpcRegion; }
    public void setVpcRegion(String vpcRegion) { this.vpcRegion = vpcRegion; }

    public String key() {
        return vpcRegion + "/" + vpcId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ZoneVpc other)) return false;
        return Objects.equals(vpcId, other.vpcId) && Objects.equals(vpcRegion, other.vpcRegion);
    }

    @Override
    public int hashCode() {
        return Objects.hash(vpcId, vpcRegion);
    }
}
