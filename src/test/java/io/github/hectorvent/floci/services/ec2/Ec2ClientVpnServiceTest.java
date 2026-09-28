package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.services.acm.AcmService;
import io.github.hectorvent.floci.services.ec2.model.ClientVpnAuthentication;
import io.github.hectorvent.floci.services.ec2.model.ClientVpnAuthorizationRule;
import io.github.hectorvent.floci.services.ec2.model.ClientVpnEndpoint;
import io.github.hectorvent.floci.services.ec2.model.ClientVpnRoute;
import io.github.hectorvent.floci.services.ec2.model.ClientVpnTargetNetwork;
import io.github.hectorvent.floci.services.ec2.model.Tag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class Ec2ClientVpnServiceTest {

    private static final String REGION = "us-east-1";
    private static final String CERTIFICATE =
            "arn:aws:acm:us-east-1:000000000000:certificate/11111111-2222-3333-4444-555555555555";

    private Ec2Service ec2;
    private Ec2ClientVpnService vpn;
    private String vpcId;
    private String firstSubnet;
    private String secondSubnet;

    @BeforeEach
    void setUp() {
        ec2 = Ec2DefaultSecurityGroupTest.newService();
        vpn = new Ec2ClientVpnService(ec2, AccountAwareStorageBackend.inMemory("000000000000"), () -> null);
        vpcId = ec2.createVpc(REGION, "10.171.0.0/16", false).getVpcId();
        firstSubnet = ec2.createSubnet(REGION, vpcId, "10.171.1.0/24", "us-east-1a").getSubnetId();
        secondSubnet = ec2.createSubnet(REGION, vpcId, "10.171.2.0/24", "us-east-1b").getSubnetId();
    }

    @Test
    void createsEndpointPendingAssociationWithAwsDefaults() {
        ClientVpnEndpoint endpoint = vpn.createEndpoint(REGION, settings());

        assertTrue(endpoint.getClientVpnEndpointId().startsWith("cvpn-endpoint-"));
        assertEquals("pending-associate", endpoint.getStatus());
        assertEquals("*." + endpoint.getClientVpnEndpointId() + ".prod.clientvpn.us-east-1.amazonaws.com",
                endpoint.getDnsName());
        assertEquals("udp", endpoint.getTransportProtocol());
        assertEquals(443, endpoint.getVpnPort());
        assertEquals(24, endpoint.getSessionTimeoutHours());
        assertEquals("disabled", endpoint.getSelfServicePortal());
        assertEquals("ipv4", endpoint.getTrafficIpAddressType());
        assertFalse(endpoint.isConnectionLogEnabled());
        assertNull(endpoint.getVpcId());
        assertTrue(endpoint.getSecurityGroupIds().isEmpty());
        assertEquals(List.of(endpoint.getClientVpnEndpointId()), vpn.describeEndpoints(REGION, List.of(), Map.of())
                .stream().map(ClientVpnEndpoint::getClientVpnEndpointId).toList());
    }

    @Test
    void endpointWithVpcButNoGroupsUsesTheVpcDefaultGroup() {
        Ec2ClientVpnService.EndpointSettings request = settings();
        request.vpcId = vpcId;

        ClientVpnEndpoint endpoint = vpn.createEndpoint(REGION, request);

        assertEquals(vpcId, endpoint.getVpcId());
        assertEquals(List.of(defaultGroupId()), endpoint.getSecurityGroupIds());
    }

    @Test
    void createRejectsInvalidReferencesAndSettings() {
        Ec2ClientVpnService.EndpointSettings missingVpc = settings();
        missingVpc.vpcId = "vpc-0123456789abcdef0";
        assertCode("InvalidVpcID.NotFound", () -> vpn.createEndpoint(REGION, missingVpc));

        Ec2ClientVpnService.EndpointSettings groupsWithoutVpc = settings();
        groupsWithoutVpc.securityGroupIds = List.of(defaultGroupId());
        assertCode("InvalidParameterValue", () -> vpn.createEndpoint(REGION, groupsWithoutVpc));

        String otherVpc = ec2.createVpc(REGION, "10.200.0.0/16", false).getVpcId();
        String foreignGroup = ec2.createSecurityGroup(REGION, "foreign", "foreign", otherVpc).getGroupId();
        Ec2ClientVpnService.EndpointSettings foreign = settings();
        foreign.vpcId = vpcId;
        foreign.securityGroupIds = List.of(foreignGroup);
        assertCode("InvalidParameterValue", () -> vpn.createEndpoint(REGION, foreign));

        Ec2ClientVpnService.EndpointSettings wideCidr = settings();
        wideCidr.clientCidrBlock = "172.16.0.0/8";
        assertCode("InvalidParameterValue", () -> vpn.createEndpoint(REGION, wideCidr));

        Ec2ClientVpnService.EndpointSettings badPort = settings();
        badPort.vpnPort = 8443;
        assertCode("InvalidParameterValue", () -> vpn.createEndpoint(REGION, badPort));

        Ec2ClientVpnService.EndpointSettings otherRegionCertificate = settings();
        otherRegionCertificate.serverCertificateArn = CERTIFICATE.replace("us-east-1", "eu-west-1");
        assertCode("InvalidParameterValue", () -> vpn.createEndpoint(REGION, otherRegionCertificate));

        Ec2ClientVpnService.EndpointSettings noAuthentication = settings();
        noAuthentication.authenticationOptions.clear();
        assertCode("MissingParameter", () -> vpn.createEndpoint(REGION, noAuthentication));

        assertTrue(vpn.describeEndpoints(REGION, List.of(), Map.of()).isEmpty());
    }

    @Test
    void clientTokenReplaysTheOriginalEndpoint() {
        Ec2ClientVpnService.EndpointSettings request = settings();
        request.clientToken = "token-1";

        ClientVpnEndpoint first = vpn.createEndpoint(REGION, request);
        ClientVpnEndpoint second = vpn.createEndpoint(REGION, request);

        assertEquals(first.getClientVpnEndpointId(), second.getClientVpnEndpointId());
        assertEquals(1, vpn.describeEndpoints(REGION, List.of(), Map.of()).size());
    }

    @Test
    void modifyUpdatesSettingsAndRestoresDefaults() {
        Ec2ClientVpnService.EndpointSettings request = settings();
        request.vpcId = vpcId;
        String endpointId = vpn.createEndpoint(REGION, request).getClientVpnEndpointId();
        String group = ec2.createSecurityGroup(REGION, "vpn", "vpn", vpcId).getGroupId();

        Ec2ClientVpnService.EndpointSettings changes = new Ec2ClientVpnService.EndpointSettings();
        changes.description = "updated";
        changes.dnsServersEnabled = true;
        changes.dnsServers = List.of("8.8.8.8", "8.8.4.4");
        changes.vpnPort = 1194;
        changes.splitTunnel = true;
        changes.vpcId = vpcId;
        changes.securityGroupIds = List.of(group);
        changes.sessionTimeoutHours = 10;
        changes.connectionLogEnabled = true;
        changes.connectionLogGroup = "vpn-logs";
        changes.clientLoginBannerEnabled = true;
        changes.clientLoginBannerText = "hello";
        vpn.modifyEndpoint(REGION, endpointId, changes);

        ClientVpnEndpoint modified = vpn.requireEndpoint(REGION, endpointId);
        assertEquals("updated", modified.getDescription());
        assertEquals(List.of("8.8.8.8", "8.8.4.4"), modified.getDnsServers());
        assertEquals(1194, modified.getVpnPort());
        assertTrue(modified.isSplitTunnel());
        assertEquals(List.of(group), modified.getSecurityGroupIds());
        assertEquals(10, modified.getSessionTimeoutHours());
        assertEquals("vpn-logs", modified.getConnectionLogGroup());
        assertTrue(modified.getConnectionLogStream().startsWith(endpointId + "-us-east-1-"));
        assertEquals("hello", modified.getClientLoginBannerText());
        assertEquals(endpointId, vpn.dependentEndpoint(REGION, group).orElseThrow());

        Ec2ClientVpnService.EndpointSettings defaults = new Ec2ClientVpnService.EndpointSettings();
        defaults.description = "";
        defaults.dnsServersEnabled = false;
        defaults.connectionLogEnabled = false;
        defaults.clientLoginBannerEnabled = false;
        defaults.vpcId = vpcId;
        vpn.modifyEndpoint(REGION, endpointId, defaults);

        ClientVpnEndpoint restored = vpn.requireEndpoint(REGION, endpointId);
        assertEquals("", restored.getDescription());
        assertTrue(restored.getDnsServers().isEmpty());
        assertFalse(restored.isConnectionLogEnabled());
        assertNull(restored.getConnectionLogGroup());
        assertFalse(restored.isClientLoginBannerEnabled());
        assertNull(restored.getClientLoginBannerText());
        assertEquals(List.of(defaultGroupId()), restored.getSecurityGroupIds());
    }

    @Test
    void rejectedModificationLeavesTheEndpointUnchanged() {
        String endpointId = vpn.createEndpoint(REGION, settings()).getClientVpnEndpointId();
        Ec2ClientVpnService.EndpointSettings changes = new Ec2ClientVpnService.EndpointSettings();
        changes.description = "should not stick";
        changes.clientConnectEnabled = true;

        assertCode("MissingParameter", () -> vpn.modifyEndpoint(REGION, endpointId, changes));

        assertNull(vpn.requireEndpoint(REGION, endpointId).getDescription());
        assertFalse(vpn.requireEndpoint(REGION, endpointId).isClientConnectEnabled());
    }

    @Test
    void associationMakesEndpointAvailableAndAddsTheVpcRoute() {
        String endpointId = vpn.createEndpoint(REGION, settings()).getClientVpnEndpointId();

        ClientVpnTargetNetwork association = vpn.associateTargetNetwork(REGION, endpointId, firstSubnet, null);

        assertTrue(association.getAssociationId().startsWith("cvpn-assoc-"));
        assertEquals(vpcId, association.getVpcId());
        ClientVpnEndpoint endpoint = vpn.requireEndpoint(REGION, endpointId);
        assertEquals("available", endpoint.getStatus());
        assertEquals(vpcId, endpoint.getVpcId());
        assertEquals(List.of(defaultGroupId()), endpoint.getSecurityGroupIds());
        List<ClientVpnTargetNetwork> networks = vpn.describeTargetNetworks(REGION, endpointId, List.of(), Map.of());
        assertEquals(1, networks.size());
        assertEquals("associated", networks.getFirst().getStatus());
        ClientVpnRoute route = vpn.describeRoutes(REGION, endpointId, Map.of()).getFirst();
        assertEquals("10.171.0.0/16", route.getDestinationCidr());
        assertEquals(firstSubnet, route.getTargetSubnet());
        assertEquals("associate", route.getOrigin());
        assertEquals("active", route.getStatus());
    }

    @Test
    void associationValidatesTheSubnet() {
        String endpointId = vpn.createEndpoint(REGION, settings()).getClientVpnEndpointId();
        vpn.associateTargetNetwork(REGION, endpointId, firstSubnet, null);
        String sameZone = ec2.createSubnet(REGION, vpcId, "10.171.3.0/24", "us-east-1a").getSubnetId();
        String otherVpc = ec2.createVpc(REGION, "10.172.0.0/16", false).getVpcId();
        String foreignSubnet = ec2.createSubnet(REGION, otherVpc, "10.172.1.0/24", "us-east-1c").getSubnetId();

        assertCode("InvalidClientVpnDuplicateAssociationException",
                () -> vpn.associateTargetNetwork(REGION, endpointId, firstSubnet, null));
        assertCode("InvalidClientVpnSubnetId.DuplicateAz",
                () -> vpn.associateTargetNetwork(REGION, endpointId, sameZone, null));
        assertCode("InvalidParameterValue",
                () -> vpn.associateTargetNetwork(REGION, endpointId, foreignSubnet, null));
        assertCode("InvalidClientVpnSubnetId.NotFound",
                () -> vpn.associateTargetNetwork(REGION, endpointId, "subnet-0123456789abcdef0", null));
        assertCode("InvalidSubnetID.Malformed",
                () -> vpn.associateTargetNetwork(REGION, endpointId, "not-a-subnet", null));
        assertCode("InvalidClientVpnEndpointId.NotFound",
                () -> vpn.associateTargetNetwork(REGION, "cvpn-endpoint-00000000000000000", secondSubnet, null));

        Ec2ClientVpnService.EndpointSettings overlapping = settings();
        overlapping.clientCidrBlock = "10.168.0.0/14";
        String overlappingId = vpn.createEndpoint(REGION, overlapping).getClientVpnEndpointId();
        assertCode("InvalidClientVpnSubnetId.OverlappingCidr",
                () -> vpn.associateTargetNetwork(REGION, overlappingId, secondSubnet, null));
    }

    @Test
    void disassociationRemovesItsRoutesAndReturnsToPendingAssociate() {
        String endpointId = vpn.createEndpoint(REGION, settings()).getClientVpnEndpointId();
        String first = vpn.associateTargetNetwork(REGION, endpointId, firstSubnet, null).getAssociationId();
        String second = vpn.associateTargetNetwork(REGION, endpointId, secondSubnet, null).getAssociationId();
        vpn.createRoute(REGION, endpointId, "192.168.10.0/24", firstSubnet, "manual", null);

        vpn.disassociateTargetNetwork(REGION, endpointId, first);

        assertEquals("available", vpn.requireEndpoint(REGION, endpointId).getStatus());
        assertTrue(vpn.describeRoutes(REGION, endpointId, Map.of()).stream()
                .noneMatch(route -> firstSubnet.equals(route.getTargetSubnet())));
        assertCode("InvalidClientVpnAssociationIdNotFound",
                () -> vpn.disassociateTargetNetwork(REGION, endpointId, first));

        vpn.disassociateTargetNetwork(REGION, endpointId, second);
        assertEquals("pending-associate", vpn.requireEndpoint(REGION, endpointId).getStatus());
        assertTrue(vpn.describeRoutes(REGION, endpointId, Map.of()).isEmpty());
    }

    @Test
    void authorizationRulesLifecycle() {
        String endpointId = vpn.createEndpoint(REGION, settings()).getClientVpnEndpointId();

        vpn.authorizeIngress(REGION, endpointId, "10.171.0.0/16", null, true, "all", null);
        vpn.authorizeIngress(REGION, endpointId, "10.171.0.0/16", "S-1-5-21", null, null, null);

        List<ClientVpnAuthorizationRule> rules = vpn.describeAuthorizationRules(REGION, endpointId, Map.of());
        assertEquals(2, rules.size());
        assertTrue(rules.stream().allMatch(rule -> "active".equals(rule.getStatus())));
        assertCode("InvalidClientVpnDuplicateAuthorizationRule",
                () -> vpn.authorizeIngress(REGION, endpointId, "10.171.0.0/16", null, true, "again", null));
        assertCode("InvalidParameterValue",
                () -> vpn.authorizeIngress(REGION, endpointId, "10.172.0.0/16", "S-1", true, null, null));
        assertCode("InvalidParameterValue",
                () -> vpn.authorizeIngress(REGION, endpointId, "not-a-cidr", null, true, null, null));

        vpn.revokeIngress(REGION, endpointId, "10.171.0.0/16", null, true);

        List<ClientVpnAuthorizationRule> remaining = vpn.describeAuthorizationRules(REGION, endpointId, Map.of());
        assertEquals(1, remaining.size());
        assertEquals("S-1-5-21", remaining.getFirst().getGroupId());
        assertCode("InvalidClientVpnEndpointAuthorizationRuleNotFound",
                () -> vpn.revokeIngress(REGION, endpointId, "10.171.0.0/16", null, true));
        assertEquals(1, vpn.describeAuthorizationRules(REGION, endpointId,
                Map.of("group-id", List.of("S-1-5-21"))).size());
    }

    @Test
    void routesRequireAnActiveAssociationAndOnlyManualRoutesCanBeDeleted() {
        String endpointId = vpn.createEndpoint(REGION, settings()).getClientVpnEndpointId();
        assertCode("InvalidClientVpnActiveAssociationNotFound",
                () -> vpn.createRoute(REGION, endpointId, "0.0.0.0/0", firstSubnet, null, null));
        vpn.associateTargetNetwork(REGION, endpointId, firstSubnet, null);

        vpn.createRoute(REGION, endpointId, "192.168.10.0/24", firstSubnet, "Initial route", null);

        ClientVpnRoute manual = vpn.describeRoutes(REGION, endpointId, Map.of("origin", List.of("add-route")))
                .getFirst();
        assertEquals("Initial route", manual.getDescription());
        assertEquals("active", manual.getStatus());
        assertCode("InvalidClientVpnDuplicateRoute",
                () -> vpn.createRoute(REGION, endpointId, "192.168.10.0/24", firstSubnet, null, null));
        assertCode("InvalidParameterValue",
                () -> vpn.deleteRoute(REGION, endpointId, "10.171.0.0/16", firstSubnet));

        vpn.deleteRoute(REGION, endpointId, "192.168.10.0/24", firstSubnet);

        assertCode("InvalidClientVpnRouteNotFound",
                () -> vpn.deleteRoute(REGION, endpointId, "192.168.10.0/24", firstSubnet));
        assertEquals(List.of("associate"), vpn.describeRoutes(REGION, endpointId, Map.of()).stream()
                .map(ClientVpnRoute::getOrigin).toList());
    }

    @Test
    void deleteRequiresDisassociationAndBlocksNetworkDeletionUntilThen() {
        Ec2ClientVpnService.EndpointSettings request = settings();
        request.vpcId = vpcId;
        String endpointId = vpn.createEndpoint(REGION, request).getClientVpnEndpointId();
        ec2.createTags(REGION, List.of(endpointId), List.of(new Tag("Environment", "test")));
        String associationId = vpn.associateTargetNetwork(REGION, endpointId, firstSubnet, null).getAssociationId();

        assertEquals(endpointId, vpn.dependentEndpoint(REGION, firstSubnet).orElseThrow());
        assertEquals(endpointId, vpn.dependentEndpoint(REGION, vpcId).orElseThrow());
        assertTrue(vpn.dependentEndpoint(REGION, secondSubnet).isEmpty());
        assertCode("DependencyViolation", () -> vpn.deleteEndpoint(REGION, endpointId));

        vpn.disassociateTargetNetwork(REGION, endpointId, associationId);
        vpn.deleteEndpoint(REGION, endpointId);

        assertCode("InvalidClientVpnEndpointId.NotFound", () -> vpn.requireEndpoint(REGION, endpointId));
        assertCode("InvalidClientVpnEndpointId.NotFound",
                () -> vpn.describeEndpoints(REGION, List.of(endpointId), Map.of()));
        assertTrue(ec2.resourceTags(endpointId).isEmpty());
        assertTrue(vpn.dependentEndpoint(REGION, vpcId).isEmpty());
    }

    @Test
    void describeFiltersByTagAndIsRegionScoped() {
        String tagged = vpn.createEndpoint(REGION, settings()).getClientVpnEndpointId();
        vpn.createEndpoint(REGION, settings());
        ec2.createTags(REGION, List.of(tagged), List.of(new Tag("alchemy::id", "Endpoint")));

        assertEquals(List.of(tagged), vpn.describeEndpoints(REGION, List.of(),
                        Map.of("tag:alchemy::id", List.of("Endpoint"))).stream()
                .map(ClientVpnEndpoint::getClientVpnEndpointId).toList());
        assertEquals(2, vpn.describeEndpoints(REGION, List.of(), Map.of("transport-protocol", List.of("udp"))).size());
        assertCode("InvalidParameterValue",
                () -> vpn.describeEndpoints(REGION, List.of(), Map.of("bogus", List.of("x"))));
        assertTrue(vpn.describeEndpoints("eu-west-1", List.of(), Map.of()).isEmpty());
        assertCode("InvalidClientVpnEndpointId.NotFound", () -> vpn.requireEndpoint("eu-west-1", tagged));
    }

    @Test
    void certificatesAreValidatedAgainstAcmAndMarkedInUse() {
        AcmService acm = mock(AcmService.class);
        Ec2ClientVpnService withAcm = new Ec2ClientVpnService(ec2,
                AccountAwareStorageBackend.inMemory("000000000000"), () -> acm);
        String missing = CERTIFICATE.replace("11111111", "99999999");
        doThrow(new AwsException("ResourceNotFoundException", "missing", 404))
                .when(acm).describeCertificate(eq(missing), anyString());
        Ec2ClientVpnService.EndpointSettings unknown = settings();
        unknown.serverCertificateArn = missing;
        assertCode("InvalidParameterValue", () -> withAcm.createEndpoint(REGION, unknown));

        ClientVpnEndpoint endpoint = withAcm.createEndpoint(REGION, settings());
        String arn = withAcm.endpointArn(endpoint);
        assertEquals("arn:aws:ec2:us-east-1:000000000000:client-vpn-endpoint/" + endpoint.getClientVpnEndpointId(),
                arn);
        verify(acm).addInUseBy(CERTIFICATE, arn, REGION);

        withAcm.deleteEndpoint(REGION, endpoint.getClientVpnEndpointId());
        verify(acm).removeInUseBy(CERTIFICATE, arn, REGION);
    }

    @Test
    void mutationsDoNotLeakIntoPreviouslyReadSnapshots() {
        String endpointId = vpn.createEndpoint(REGION, settings()).getClientVpnEndpointId();
        ClientVpnEndpoint before = vpn.requireEndpoint(REGION, endpointId);

        vpn.associateTargetNetwork(REGION, endpointId, firstSubnet, null);

        assertTrue(before.getTargetNetworks().isEmpty());
        assertEquals("pending-associate", before.getStatus());
        assertEquals(1, vpn.requireEndpoint(REGION, endpointId).getTargetNetworks().size());
    }

    private String defaultGroupId() {
        return ec2.describeSecurityGroups(REGION, List.of(), List.of("default"), Map.of("vpc-id", List.of(vpcId)))
                .getFirst().getGroupId();
    }

    private static Ec2ClientVpnService.EndpointSettings settings() {
        Ec2ClientVpnService.EndpointSettings request = new Ec2ClientVpnService.EndpointSettings();
        request.clientCidrBlock = "172.20.0.0/22";
        request.serverCertificateArn = CERTIFICATE;
        ClientVpnAuthentication authentication = new ClientVpnAuthentication();
        authentication.setType("certificate-authentication");
        authentication.setClientRootCertificateChainArn(CERTIFICATE);
        request.authenticationOptions.add(authentication);
        return request;
    }

    private static void assertCode(String code, Executable executable) {
        assertEquals(code, assertThrows(AwsException.class, executable).getErrorCode());
    }
}
