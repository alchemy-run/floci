package io.github.hectorvent.floci.services.ec2;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Frame;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.services.ec2.model.GroupIdentifier;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.InstanceNetworkInterface;
import io.github.hectorvent.floci.services.ec2.model.InstanceState;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplateData;
import io.github.hectorvent.floci.services.ec2.net.VpcNetworkManager;
import io.github.hectorvent.floci.services.ec2.model.IpPermission;
import io.github.hectorvent.floci.services.ec2.model.IpRange;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class SecurityGroupFirewallDockerIntegrationTest {

    // A denied connection is dropped without a reply, so it only ends when nc's timeout expires;
    // a permitted one connects in tens of milliseconds, so one second is ample to call it denied.
    private static final int PERMITTED_TIMEOUT_SECONDS = 2;
    private static final int DENIED_TIMEOUT_SECONDS = 1;

    @Inject SecurityGroupFirewallManager firewall;
    @Inject ContainerLifecycleManager lifecycle;
    @Inject ContainerBuilder builder;
    @Inject DockerClient docker;
    @Inject Ec2ContainerManager instances;
    @Inject VpcNetworkManager networks;

    @ParameterizedTest
    @CsvSource({"ubuntu:24.04,", "amazonlinux:2023,", "ubuntu:24.04,linux/amd64"})
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void stockGuestBootstrapsWithEmptyEgressWithoutGuestDownloads(String image, String platform) throws Exception {
        Assumptions.assumeTrue(firewall.enabled());
        try {
            Assumptions.assumeTrue("linux".equalsIgnoreCase(docker.infoCmd().exec().getOsType()));
        } catch (Exception e) {
            Assumptions.abort("Docker daemon is unavailable");
        }
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String region = "us-east-1";
        String vpc = "vpc-offline-" + suffix;
        String subnet = "subnet-offline-" + suffix;
        String eniId = "eni-offline-" + suffix;
        networks.declareVpc(region, vpc, "10.44.0.0/16");
        networks.declareSubnet(region, vpc, subnet, "10.44.0.0/24");
        SecurityGroup denied = new SecurityGroup();
        denied.setGroupId("sg-offline-" + suffix);
        denied.setVpcId(vpc);
        denied.setOwnerId("000000000000");
        Instance guest = new Instance();
        guest.setInstanceId("i-offline-" + suffix);
        guest.setRegion(region);
        guest.setVpcId(vpc);
        guest.setSubnetId(subnet);
        guest.setState(InstanceState.pending());
        guest.setPrivateIpAddress("10.44.0.77");
        guest.setLogicalPrivateIpAddress("10.44.0.77");
        guest.setSecurityGroups(List.of(new GroupIdentifier(denied.getGroupId(), "offline")));
        InstanceNetworkInterface eni = new InstanceNetworkInterface();
        eni.setNetworkInterfaceId(eniId);
        eni.setOwnerId("000000000000");
        eni.setPrivateIpAddress("10.44.0.77");
        guest.setNetworkInterfaces(List.of(eni));
        // This regression tests bootstrap networking, independently of the host's IMDS reachability.
        LaunchTemplateData.MetadataOptions metadata = new LaunchTemplateData.MetadataOptions();
        metadata.setHttpEndpoint("disabled");
        guest.setMetadataOptions(metadata);
        guest.setUserData("""
                #!/bin/sh
                set -eu
                curl --version >/dev/null
                test -s /etc/ssl/certs/ca-certificates.crt || test -s /etc/pki/tls/certs/ca-bundle.crt
                test -d /etc/systemd/system
                test -x /usr/local/sbin/systemctl
                systemctl daemon-reload
                touch /var/lib/offline-bootstrap-ready
                """);
        SecurityGroupFirewallManager.Namespace target = null;
        String server = null;
        String targetEni = "eni-offline-target-" + suffix;
        try {
            target = firewall.createNamespace("ec2", "offline-target-" + suffix, "000000000000",
                    region, Optional.empty(), Map.of());
            SecurityGroup targetGroup = group("sg-offline-target-" + suffix, false, "0.0.0.0/0");
            targetGroup.setVpcId(vpc);
            firewall.register(new SecurityGroupNftCompiler.Endpoint("000000000000", region, vpc,
                    targetEni, "10.44.0.78", target.transportAddress(), Set.of(targetGroup.getGroupId()),
                    List.of(targetGroup)), target.helperId(), Map.of());
            server = lifecycle.createAndStart(builder.newContainer("alpine:3.21")
                    .withName("floci-offline-target-" + suffix)
                    .withNetworkMode("container:" + target.helperId())
                    .withEntrypoint(List.of("sh", "-c"))
                    .withCmd(List.of("while true; do printf 'HTTP/1.1 200 OK\\r\\nContent-Length: 2\\r\\n"
                            + "Connection: close\\r\\n\\r\\nok' | nc -l -p 8080; done"))
                    .build()).containerId();
            for (int attempt = 0; attempt < 20 && connect(server, "127.0.0.1") != 0; attempt++) {
                Thread.sleep(100);
            }
            assertEquals(0, connect(server, "127.0.0.1"));
            assertEquals("ok", fetch(target.helperId(), "127.0.0.1"));
            assertEquals(0, execute(target.helperId(), "sh", "-ec", "command -v ip; command -v python3"));
            ResolvedAmiImage requestedImage = new ResolvedAmiImage(image, "minimal", false, platform);
            instances.launch(guest, requestedImage, null, region,
                    Set.of(), List.of(denied), Map.of());
            for (int attempt = 0; attempt < 180 && "pending".equals(guest.getState().getName()); attempt++) {
                Thread.sleep(500);
            }
            assertEquals("running", guest.getState().getName());
            assertFalse(guest.isContainerLaunchFailed());
            var inspect = docker.inspectContainerCmd(guest.getDockerContainerId()).exec();
            ResolvedAmiImage prepared = instances.prepareImage(requestedImage);
            assertTrue(prepared.dockerImage().contains("floci/ec2-bootstrap:"));
            if (platform != null) {
                assertEquals(platform, docker.inspectImageCmd(prepared.dockerImage()).exec().getOs() + "/"
                        + docker.inspectImageCmd(prepared.dockerImage()).exec().getArch());
            }
            assertEquals(docker.inspectImageCmd(prepared.dockerImage()).exec().getId(), inspect.getConfig().getImage());
            assertTrue(inspect.getHostConfig().getNetworkMode().startsWith("container:"));
            assertFalse(Boolean.TRUE.equals(inspect.getHostConfig().getPrivileged()));
            for (int attempt = 0; attempt < 40 && execute(guest.getDockerContainerId(),
                    "test", "-f", "/var/lib/offline-bootstrap-ready") != 0; attempt++) {
                Thread.sleep(250);
            }
            assertEquals(0, execute(guest.getDockerContainerId(), "test", "-f", "/var/lib/offline-bootstrap-ready"));
            assertNotEquals(0, execute(guest.getDockerContainerId(), "curl", "--noproxy", "*", "--max-time", "2",
                    "http://" + target.transportAddress() + ":8080/"));
            assertNotEquals(0, execute(guest.getDockerContainerId(), "curl", "--noproxy", "*", "--max-time", "2",
                    "http://10.44.0.78:8080/"));
            assertTrue(denied.getIpPermissionsEgress().isEmpty());
        } finally {
            instances.terminate(guest);
            for (int attempt = 0; attempt < 100 && !"terminated".equals(guest.getState().getName()); attempt++) {
                Thread.sleep(100);
            }
            if (server != null) {
                lifecycle.removeIfExists(server);
            }
            firewall.unregister(targetEni);
            if (target != null) {
                lifecycle.removeIfExists(target.helperId());
            }
            networks.forgetSubnet(region, subnet);
            networks.deleteVpcNetwork(region, vpc);
            assertEquals("terminated", guest.getState().getName());
        }
    }

    private int execute(String container, String... command) throws Exception {
        String id = docker.execCreateCmd(container).withAttachStdout(true).withAttachStderr(true)
                .withCmd(command).exec().getId();
        try (ResultCallback.Adapter<Frame> callback = new ResultCallback.Adapter<>()) {
            assertTrue(docker.execStartCmd(id).exec(callback).awaitCompletion(5, TimeUnit.SECONDS));
        }
        return docker.inspectExecCmd(id).exec().getExitCodeLong().intValue();
    }

    @Test
    void deniedLogicalCidrBlocksManagedPeerThenRuleUpdatePermitsIt() throws Exception {
        Assumptions.assumeTrue(firewall.enabled());
        try {
            Assumptions.assumeTrue("linux".equalsIgnoreCase(docker.infoCmd().exec().getOsType()));
        } catch (Exception e) {
            Assumptions.abort("Docker daemon is unavailable");
        }

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String targetEni = "eni-target-" + suffix;
        String sourceEni = "eni-source-" + suffix;
        List<SecurityGroupFirewallManager.Namespace> namespaces = new ArrayList<>();
        List<String> workers = new ArrayList<>();
        try {
            SecurityGroupFirewallManager.Namespace target = firewall.createNamespace("ec2", "sg-target-" + suffix, "000000000000",
                    "us-east-1", Optional.empty(), Map.of());
            namespaces.add(target);
            SecurityGroupFirewallManager.Namespace source = firewall.createNamespace("ec2", "sg-source-" + suffix, "000000000000",
                    "us-east-1", Optional.empty(), Map.of());
            namespaces.add(source);

            SecurityGroup targetGroup = group("sg-target", false, "10.0.0.0/24");
            SecurityGroup sourceGroup = group("sg-source", true, "0.0.0.0/0");
            firewall.register(endpoint(targetEni, "10.0.0.2", target, targetGroup),
                    target.helperId(), Map.of());
            firewall.register(endpoint(sourceEni, "10.1.0.2", source, sourceGroup),
                    source.helperId(), Map.of());

            workers.add(lifecycle.createAndStart(builder.newContainer("alpine:3.21")
                    .withName("floci-sg-test-target-" + suffix)
                    .withNetworkMode("container:" + target.helperId())
                    .withEntrypoint(List.of("sh", "-c"))
                    .withCmd(List.of("exec nc -lk -p 8080 -e /bin/cat"))
                    .build()).containerId());
            workers.add(lifecycle.createAndStart(builder.newContainer("alpine:3.21")
                    .withName("floci-sg-test-source-" + suffix)
                    .withNetworkMode("container:" + source.helperId())
                    .withEntrypoint(List.of("sleep"))
                    .withCmd(List.of("600"))
                    .build()).containerId());

            for (int attempt = 0; attempt < 20 && connect(workers.getFirst(), "127.0.0.1") != 0; attempt++) {
                Thread.sleep(100);
            }
            assertEquals(0, connect(workers.getFirst(), "127.0.0.1"));
            assertNotEquals(0, connect(workers.get(1), target.transportAddress(), DENIED_TIMEOUT_SECONDS));
            assertNotEquals(0, connect(workers.get(1), "10.0.0.2", DENIED_TIMEOUT_SECONDS));

            IpRange corrected = new IpRange();
            corrected.setCidrIp("10.1.0.0/24");
            targetGroup.getIpPermissions().getFirst().getIpRanges().add(corrected);
            firewall.refreshPolicies("us-east-1", Map.of("sg-target", targetGroup,
                    "sg-source", sourceGroup), Map.of());
            assertEquals(0, connect(workers.get(1), target.transportAddress()));
            assertEquals(0, connect(workers.get(1), "10.0.0.2"));

            firewall.quarantineSurvivingNamespaces();
            assertNotEquals(0, connect(workers.get(1), target.transportAddress(), DENIED_TIMEOUT_SECONDS));
            firewall.refreshPolicies("us-east-1", Map.of("sg-target", targetGroup,
                    "sg-source", sourceGroup), Map.of());
            assertEquals(0, connect(workers.get(1), target.transportAddress()));

            SecurityGroup replacement = group("sg-replacement", false, "192.0.2.0/24");
            Map<String, SecurityGroup> groups = Map.of("sg-target", targetGroup, "sg-source", sourceGroup,
                    "sg-replacement", replacement);
            firewall.updateGroups(targetEni, Set.of("sg-replacement"), groups, Map.of());
            assertNotEquals(0, connect(workers.get(1), target.transportAddress(), DENIED_TIMEOUT_SECONDS));
            firewall.updateGroups(targetEni, Set.of("sg-target"), groups, Map.of());
            assertEquals(0, connect(workers.get(1), target.transportAddress()));

            docker.stopContainerCmd(target.helperId()).withTimeout(0).exec();
            firewall.refreshPolicies("us-east-1", groups, Map.of());
            assertFalse(docker.inspectContainerCmd(workers.getFirst()).exec().getState().getRunning());
        } finally {
            for (String worker : workers) {
                lifecycle.removeIfExists(worker);
            }
            firewall.unregister(sourceEni);
            firewall.unregister(targetEni);
            for (SecurityGroupFirewallManager.Namespace namespace : namespaces) {
                lifecycle.removeIfExists(namespace.helperId());
            }
        }
    }

    @Test
    void overlappingLogicalAddressesConnectOnlyWithinTheirVpc() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        List<SecurityGroupFirewallManager.Namespace> namespaces = new ArrayList<>();
        List<String> workers = new ArrayList<>();
        List<String> enis = new ArrayList<>();
        try {
            for (int vpc = 0; vpc < 2; vpc++) {
                String vpcId = "vpc-translation-" + suffix + "-" + vpc;
                for (int host = 0; host < 2; host++) {
                    String eni = "eni-translation-" + suffix + "-" + vpc + "-" + host;
                    SecurityGroupFirewallManager.Namespace namespace = firewall.createNetworkNamespace(
                            "ec2", eni, "000000000000", "us-east-1", Optional.empty(), Map.of());
                    namespaces.add(namespace);
                    enis.add(eni);
                    firewall.registerNetworkEndpoint(new SecurityGroupNftCompiler.Endpoint(
                            "000000000000", "us-east-1", vpcId, eni,
                            host == 0 ? "10.0.1.77" : "10.0.1.78", namespace.transportAddress(), Set.of(), List.of()),
                            namespace.helperId());
                    if (host == 0) {
                        workers.add(lifecycle.createAndStart(builder.newContainer("alpine:3.21")
                                .withName("floci-translation-" + suffix + "-" + vpc)
                                .withNetworkMode("container:" + namespace.helperId())
                                .withEntrypoint(List.of("sh", "-c"))
                                .withCmd(List.of("while true; do printf 'HTTP/1.1 200 OK\\r\\nContent-Length: "
                                        + vpcId.length() + "\\r\\nConnection: close\\r\\n\\r\\n" + vpcId
                                        + "' | nc -l -p 8080; done"))
                                .build()).containerId());
                    }
                }
            }
            for (String server : workers) {
                for (int attempt = 0; attempt < 20 && connect(server, "127.0.0.1") != 0; attempt++) {
                    Thread.sleep(100);
                }
                assertEquals(0, connect(server, "127.0.0.1"));
            }
            assertEquals("vpc-translation-" + suffix + "-0", fetch(namespaces.get(1).helperId(), "10.0.1.77"));
            assertEquals("vpc-translation-" + suffix + "-1", fetch(namespaces.get(3).helperId(), "10.0.1.77"));
            assertNotEquals(0, connect(namespaces.get(1).helperId(), namespaces.get(2).transportAddress()));
            assertNotEquals(0, connect(namespaces.get(3).helperId(), namespaces.getFirst().transportAddress()));
            firewall.unregister(enis.get(1), "helper-from-a-previous-instance");
            assertEquals("vpc-translation-" + suffix + "-0", fetch(namespaces.get(1).helperId(), "10.0.1.77"));
        } finally {
            workers.forEach(lifecycle::removeIfExists);
            enis.forEach(firewall::unregister);
            namespaces.forEach(namespace -> lifecycle.removeIfExists(namespace.helperId()));
        }
    }

    private String fetch(String helperId, String address) throws Exception {
        String exec = docker.execCreateCmd(helperId).withAttachStdout(true).withAttachStderr(true)
                .withCmd("curl", "-fsS", "--max-time", "2", "http://" + address + ":8080/").exec().getId();
        StringBuilder body = new StringBuilder();
        try (ResultCallback.Adapter<Frame> callback = new ResultCallback.Adapter<>() {
            @Override public void onNext(Frame frame) {
                body.append(new String(frame.getPayload(), StandardCharsets.UTF_8));
            }
        }) {
            assertTrue(docker.execStartCmd(exec).exec(callback).awaitCompletion(5, TimeUnit.SECONDS));
        }
        assertEquals(0L, docker.inspectExecCmd(exec).exec().getExitCodeLong());
        return body.toString();
    }

    private int connect(String worker, String address) throws Exception {
        return connect(worker, address, PERMITTED_TIMEOUT_SECONDS);
    }

    private int connect(String worker, String address, int timeoutSeconds) throws Exception {
        String id = docker.execCreateCmd(worker).withAttachStdout(true).withAttachStderr(true)
                .withCmd("nc", "-z", "-w", String.valueOf(timeoutSeconds), address, "8080")
                .exec().getId();
        try (ResultCallback.Adapter<Frame> callback = new ResultCallback.Adapter<>()) {
            docker.execStartCmd(id).exec(callback).awaitCompletion(5, TimeUnit.SECONDS);
        }
        return docker.inspectExecCmd(id).exec().getExitCodeLong().intValue();
    }

    private static SecurityGroup group(String id, boolean egress, String cidr) {
        SecurityGroup group = new SecurityGroup();
        group.setGroupId(id);
        group.setVpcId("vpc-1");
        group.setOwnerId("000000000000");
        IpPermission permission = new IpPermission();
        permission.setIpProtocol("tcp");
        permission.setFromPort(8080);
        permission.setToPort(8080);
        IpRange range = new IpRange();
        range.setCidrIp(cidr);
        permission.getIpRanges().add(range);
        if (egress) {
            group.getIpPermissionsEgress().add(permission);
        } else {
            group.getIpPermissions().add(permission);
        }
        return group;
    }

    private static SecurityGroupNftCompiler.Endpoint endpoint(String eni, String logical,
            SecurityGroupFirewallManager.Namespace namespace, SecurityGroup group) {
        return new SecurityGroupNftCompiler.Endpoint("000000000000", "us-east-1", "vpc-1",
                eni, logical, namespace.transportAddress(), Set.of(group.getGroupId()), List.of(group));
    }
}
