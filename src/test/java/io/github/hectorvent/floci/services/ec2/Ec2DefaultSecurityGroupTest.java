package io.github.hectorvent.floci.services.ec2;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ec2.model.IpPermission;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroupRule;
import io.github.hectorvent.floci.services.ec2.model.UserIdGroupPair;
import io.github.hectorvent.floci.services.ec2.portforward.Ec2PortForwardManager;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A VPC's default security group starts with the rules AWS gives it and cannot be deleted directly. */
class Ec2DefaultSecurityGroupTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";

    @Test
    void newVpcDefaultGroupAllowsAllTrafficFromItselfAndToAnywhere() {
        Ec2Service service = newService();
        String vpcId = service.createVpc(REGION, "10.43.0.0/16", false).getVpcId();
        SecurityGroup group = defaultGroup(service, vpcId);

        assertDefaultRules(service, group);
    }

    @Test
    void defaultVpcDefaultGroupAllowsAllTrafficFromItselfAndToAnywhere() {
        Ec2Service service = newService();
        SecurityGroup group = defaultGroup(service, Ec2Service.defaultVpcId(REGION));

        assertDefaultRules(service, group);
    }

    @Test
    void selfReferencingIngressCanBeRevokedByIdAndReauthorized() {
        Ec2Service service = newService();
        String vpcId = service.createVpc(REGION, "10.44.0.0/16", false).getVpcId();
        String groupId = defaultGroup(service, vpcId).getGroupId();
        SecurityGroupRule self = ingressRules(service, groupId).getFirst();

        service.revokeSecurityGroupIngress(REGION, groupId, List.of(), List.of(self.getSecurityGroupRuleId()));

        assertTrue(ingressRules(service, groupId).isEmpty());
        assertTrue(defaultGroup(service, vpcId).getIpPermissions().isEmpty());

        IpPermission permission = new IpPermission();
        permission.setIpProtocol("-1");
        UserIdGroupPair pair = new UserIdGroupPair();
        pair.setGroupId(groupId);
        permission.getUserIdGroupPairs().add(pair);
        List<SecurityGroupRule> restored = service.authorizeSecurityGroupIngress(REGION, groupId, List.of(permission));

        assertEquals(1, restored.size());
        assertNotEquals(self.getSecurityGroupRuleId(), restored.getFirst().getSecurityGroupRuleId());
        assertEquals(groupId, restored.getFirst().getReferencedGroupInfo().getGroupId());
        assertEquals(ACCOUNT, restored.getFirst().getReferencedGroupInfo().getUserId());
    }

    @Test
    void selfReferencingIngressCanBeRevokedByPermission() {
        Ec2Service service = newService();
        String vpcId = service.createVpc(REGION, "10.45.0.0/16", false).getVpcId();
        String groupId = defaultGroup(service, vpcId).getGroupId();

        IpPermission permission = new IpPermission();
        permission.setIpProtocol("-1");
        UserIdGroupPair pair = new UserIdGroupPair();
        pair.setGroupId(groupId);
        permission.getUserIdGroupPairs().add(pair);
        service.revokeSecurityGroupIngress(REGION, groupId, List.of(permission));

        assertTrue(ingressRules(service, groupId).isEmpty());
        assertEquals(1, service.describeSecurityGroupRules(REGION, List.of(groupId), List.of()).size());
    }

    @Test
    void defaultGroupCannotBeDeletedButIsRemovedWithItsVpc() {
        Ec2Service service = newService();
        String vpcId = service.createVpc(REGION, "10.46.0.0/16", false).getVpcId();
        String groupId = defaultGroup(service, vpcId).getGroupId();

        AwsException error = assertThrows(AwsException.class, () -> service.deleteSecurityGroup(REGION, groupId));

        assertEquals("CannotDelete", error.getErrorCode());
        assertEquals(2, service.describeSecurityGroupRules(REGION, List.of(groupId), List.of()).size());
        service.deleteVpc(REGION, vpcId);
        assertTrue(service.describeSecurityGroupRules(REGION, List.of(groupId), List.of()).isEmpty());
        assertEquals("InvalidGroup.NotFound", assertThrows(AwsException.class, () ->
                service.describeSecurityGroups(REGION, List.of(groupId), List.of(), Map.of())).getErrorCode());
    }

    @Test
    void nonDefaultGroupsStartWithoutIngress() {
        Ec2Service service = newService();
        String vpcId = service.createVpc(REGION, "10.47.0.0/16", false).getVpcId();
        String groupId = service.createSecurityGroup(REGION, "app", "app", vpcId).getGroupId();

        assertTrue(ingressRules(service, groupId).isEmpty());
        service.deleteSecurityGroup(REGION, groupId);
    }

    private static void assertDefaultRules(Ec2Service service, SecurityGroup group) {
        String groupId = group.getGroupId();
        List<SecurityGroupRule> rules = service.describeSecurityGroupRules(REGION, List.of(groupId), List.of());
        assertEquals(2, rules.size());

        SecurityGroupRule ingress = rules.stream().filter(rule -> !rule.isEgress()).findFirst().orElseThrow();
        assertTrue(ingress.getSecurityGroupRuleId().startsWith("sgr-"));
        assertEquals("-1", ingress.getIpProtocol());
        assertEquals(groupId, ingress.getReferencedGroupInfo().getGroupId());
        assertEquals(ACCOUNT, ingress.getReferencedGroupInfo().getUserId());
        assertNull(ingress.getCidrIpv4());

        SecurityGroupRule egress = rules.stream().filter(SecurityGroupRule::isEgress).findFirst().orElseThrow();
        assertTrue(egress.getSecurityGroupRuleId().startsWith("sgr-"));
        assertEquals("-1", egress.getIpProtocol());
        assertEquals("0.0.0.0/0", egress.getCidrIpv4());
        assertNull(egress.getReferencedGroupInfo());

        assertEquals(1, group.getIpPermissions().size());
        IpPermission permission = group.getIpPermissions().getFirst();
        assertEquals("-1", permission.getIpProtocol());
        assertEquals(1, permission.getUserIdGroupPairs().size());
        assertEquals(groupId, permission.getUserIdGroupPairs().getFirst().getGroupId());
        assertEquals(ACCOUNT, permission.getUserIdGroupPairs().getFirst().getUserId());
        assertTrue(permission.getIpRanges().isEmpty());
        assertEquals(1, group.getIpPermissionsEgress().size());
        assertEquals("0.0.0.0/0", group.getIpPermissionsEgress().getFirst().getIpRanges().getFirst().getCidrIp());
    }

    private static List<SecurityGroupRule> ingressRules(Ec2Service service, String groupId) {
        return service.describeSecurityGroupRules(REGION, List.of(groupId), List.of()).stream()
                .filter(rule -> !rule.isEgress())
                .toList();
    }

    private static SecurityGroup defaultGroup(Ec2Service service, String vpcId) {
        return service.describeSecurityGroups(REGION, List.of(), List.of("default"), Map.of("vpc-id", List.of(vpcId)))
                .getFirst();
    }

    static Ec2Service newService() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.Ec2ServiceConfig ec2 = mock(EmulatorConfig.Ec2ServiceConfig.class);
        when(config.defaultAccountId()).thenReturn(ACCOUNT);
        when(config.services()).thenReturn(services);
        when(services.ec2()).thenReturn(ec2);
        when(ec2.mock()).thenReturn(true);
        return new Ec2Service(config, mock(Ec2ContainerManager.class), mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());
    }

    static final class InMemoryStorageFactory extends StorageFactory {
        InMemoryStorageFactory() {
            super(null, null);
        }

        @Override
        public <V> AccountAwareStorageBackend<V> create(String serviceName, String fileName,
                                                        TypeReference<Map<String, V>> typeReference) {
            return AccountAwareStorageBackend.inMemory(ACCOUNT);
        }
    }
}
