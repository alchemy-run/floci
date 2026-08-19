package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.services.ec2.portforward.Ec2HttpPortMux;
import io.github.hectorvent.floci.services.ec2.portforward.Ec2PortForwardManager;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Ec2RunInstancesNetworkInterfaceTest {

    @Test
    void parsesPrimaryNetworkInterfaceFromAlchemyWireFormat() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("NetworkInterface.1.DeviceIndex", "0");
        params.putSingle("NetworkInterface.1.SubnetId", "subnet-abc");
        params.putSingle("NetworkInterface.1.AssociatePublicIpAddress", "true");
        params.putSingle("NetworkInterface.1.SecurityGroupId.1", "sg-web");
        params.putSingle("NetworkInterface.1.SecurityGroupId.2", "sg-ssh");

        Ec2QueryHandler.PrimaryNetworkInterface parsed = Ec2QueryHandler.parsePrimaryNetworkInterface(params);
        assertEquals("subnet-abc", parsed.subnetId());
        assertEquals(Boolean.TRUE, parsed.associatePublicIpAddress());
        assertEquals(java.util.List.of("sg-web", "sg-ssh"), parsed.securityGroupIds());
    }

    @Test
    void prefersDeviceIndexZeroWhenSeveralInterfacesArePresent() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("NetworkInterface.1.DeviceIndex", "1");
        params.putSingle("NetworkInterface.1.SubnetId", "subnet-secondary");
        params.putSingle("NetworkInterface.2.DeviceIndex", "0");
        params.putSingle("NetworkInterface.2.SubnetId", "subnet-primary");
        params.putSingle("NetworkInterface.2.AssociatePublicIpAddress", "false");

        Ec2QueryHandler.PrimaryNetworkInterface parsed = Ec2QueryHandler.parsePrimaryNetworkInterface(params);
        assertEquals("subnet-primary", parsed.subnetId());
        assertEquals(Boolean.FALSE, parsed.associatePublicIpAddress());
    }

    @Test
    void systemctlShimInstallsOnlyWhenSystemctlIsMissing() {
        String script = String.join(" ", Ec2ContainerManager.systemctlShimInstallCommand());
        assertTrue(script.contains("mkdir -p /etc/systemd/system"));
        assertTrue(script.contains("if command -v systemctl"));
        assertTrue(script.contains("ExecStartPre="));
        assertTrue(script.contains("enable|start|restart"));
        int preAt = script.indexOf("if [ -n \"$pre\" ]; then $pre");
        int envAt = script.indexOf(". \"$envfile\"");
        assertTrue(preAt >= 0 && envAt > preAt, "EnvironmentFile must be sourced after ExecStartPre");
    }

    @Test
    void prepareGuestFilesystemCreatesSystemdUnitDirectory() {
        String script = String.join(" ", Ec2ContainerManager.prepareGuestFilesystemCommand());
        assertTrue(script.contains("/etc/systemd/system"));
        assertTrue(script.contains("/usr/local/bin"));
    }

    @Test
    void nginxMuxConfigRoutesEachHostnameAndKeepsADefaultServer() {
        Map<String, String> routes = new LinkedHashMap<>();
        routes.put("i-aaa.localhost.floci.io", "172.17.0.2");
        routes.put("i-bbb.localhost.floci.io", "172.17.0.3");
        String config = Ec2HttpPortMux.nginxConfig(3000, routes);
        assertTrue(config.contains("listen 3000;"));
        assertTrue(config.contains("listen 3000 default_server;"));
        assertTrue(config.contains("server_name i-aaa.localhost.floci.io;"));
        assertTrue(config.contains("proxy_pass http://172.17.0.2:3000;"));
        assertTrue(config.contains("proxy_pass http://172.17.0.3:3000;"));
    }

    @Test
    void publicHostPrefersFlociWildcardDns() {
        io.github.hectorvent.floci.services.ec2.model.Instance inst =
                new io.github.hectorvent.floci.services.ec2.model.Instance();
        inst.setInstanceId("i-abc");
        inst.setPublicDnsName("i-abc.localhost.floci.io");
        assertEquals("i-abc.localhost.floci.io", Ec2PortForwardManager.publicHost(inst));
    }
}
