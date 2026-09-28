package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/** Wire shapes of the Client VPN actions and the default security group, as the EC2 SDKs parse them. */
class Ec2ClientVpnQueryHandlerTest {

    private static final String REGION = "us-east-1";
    private static final String CERTIFICATE =
            "arn:aws:acm:us-east-1:000000000000:certificate/11111111-2222-3333-4444-555555555555";

    private Ec2Service ec2;
    private Ec2QueryHandler handler;
    private String vpcId;
    private String subnetId;

    @BeforeEach
    void setUp() {
        ec2 = Ec2DefaultSecurityGroupTest.newService();
        Ec2ClientVpnService vpn = new Ec2ClientVpnService(ec2, AccountAwareStorageBackend.inMemory("000000000000"),
                () -> null);
        handler = new Ec2QueryHandler(ec2, mock(EmulatorConfig.class), null, null, null, null, null, vpn);
        vpcId = ec2.createVpc(REGION, "10.171.0.0/16", false).getVpcId();
        subnetId = ec2.createSubnet(REGION, vpcId, "10.171.1.0/24", "us-east-1a").getSubnetId();
    }

    @Test
    void endpointLifecycleOverTheQueryProtocol() {
        String created = ok("CreateClientVpnEndpoint", params(
                "ClientCidrBlock", "172.20.0.0/22",
                "ServerCertificateArn", CERTIFICATE,
                "Authentication.1.Type", "certificate-authentication",
                "Authentication.1.MutualAuthentication.ClientRootCertificateChainArn", CERTIFICATE,
                "DnsServers.1", "1.1.1.1",
                "VpcId", vpcId,
                "SessionTimeoutHours", "8",
                "ClientLoginBannerOptions.Enabled", "true",
                "ClientLoginBannerOptions.BannerText", "Initial banner",
                "ConnectionLogOptions.Enabled", "false",
                "ClientToken", "token",
                "TagSpecification.1.ResourceType", "client-vpn-endpoint",
                "TagSpecification.1.Tag.1.Key", "alchemy::id",
                "TagSpecification.1.Tag.1.Value", "Endpoint"));
        String endpointId = first(created, "clientVpnEndpointId");
        assertTrue(created.contains("<status><code>pending-associate</code></status>"));
        assertTrue(created.contains("<dnsName>*." + endpointId + ".prod.clientvpn.us-east-1.amazonaws.com</dnsName>"));

        String described = ok("DescribeClientVpnEndpoints", params("ClientVpnEndpointId.1", endpointId));
        assertTrue(described.contains("<clientVpnEndpoint><item><clientVpnEndpointId>" + endpointId));
        assertTrue(described.contains("<dnsServer><item>1.1.1.1</item></dnsServer>"));
        assertTrue(described.contains("<mutualAuthentication><clientRootCertificateChain>" + CERTIFICATE
                + "</clientRootCertificateChain></mutualAuthentication>"));
        assertTrue(described.contains("<connectionLogOptions><Enabled>false</Enabled></connectionLogOptions>"));
        assertTrue(described.contains("<securityGroupIdSet><item>" + defaultGroupId() + "</item></securityGroupIdSet>"));
        assertTrue(described.contains("<clientLoginBannerOptions><enabled>true</enabled>"
                + "<bannerText>Initial banner</bannerText></clientLoginBannerOptions>"));
        assertTrue(described.contains("<sessionTimeoutHours>8</sessionTimeoutHours>"));
        assertTrue(described.contains("<key>alchemy::id</key><value>Endpoint</value>"));
        assertTrue(ok("DescribeClientVpnEndpoints", params("Filter.1.Name", "tag:alchemy::id",
                "Filter.1.Value.1", "Endpoint")).contains(endpointId));

        ok("ModifyClientVpnEndpoint", params(
                "ClientVpnEndpointId", endpointId,
                "Description", "Out-of-band description",
                "DnsServers.CustomDnsServers.1", "8.8.8.8",
                "DnsServers.Enabled", "true",
                "SplitTunnel", "true"));
        String modified = ok("DescribeClientVpnEndpoints", params("ClientVpnEndpointId.1", endpointId));
        assertTrue(modified.contains("<description>Out-of-band description</description>"));
        assertTrue(modified.contains("<dnsServer><item>8.8.8.8</item></dnsServer>"));
        assertTrue(modified.contains("<splitTunnel>true</splitTunnel>"));

        String associated = ok("AssociateClientVpnTargetNetwork", params(
                "ClientVpnEndpointId", endpointId, "SubnetId", subnetId));
        String associationId = first(associated, "associationId");
        assertTrue(associated.contains("<status><code>associating</code></status>"));
        String networks = ok("DescribeClientVpnTargetNetworks", params("ClientVpnEndpointId", endpointId));
        assertTrue(networks.contains("<clientVpnTargetNetworks><item><associationId>" + associationId));
        assertTrue(networks.contains("<targetNetworkId>" + subnetId + "</targetNetworkId>"));
        assertTrue(networks.contains("<status><code>associated</code></status>"));
        String routes = ok("DescribeClientVpnRoutes", params("ClientVpnEndpointId", endpointId));
        assertTrue(routes.contains("<destinationCidr>10.171.0.0/16</destinationCidr><targetSubnet>" + subnetId));
        assertTrue(routes.contains("<origin>associate</origin>"));

        assertError("DependencyViolation", "DeleteSubnet", params("SubnetId", subnetId));
        assertError("DependencyViolation", "DeleteClientVpnEndpoint", params("ClientVpnEndpointId", endpointId));

        ok("AuthorizeClientVpnIngress", params("ClientVpnEndpointId", endpointId,
                "TargetNetworkCidr", "10.171.0.0/16", "AuthorizeAllGroups", "true", "Description", "All"));
        String rules = ok("DescribeClientVpnAuthorizationRules", params("ClientVpnEndpointId", endpointId));
        assertTrue(rules.contains("<authorizationRule><item>"));
        assertTrue(rules.contains("<accessAll>true</accessAll><destinationCidr>10.171.0.0/16</destinationCidr>"));
        assertTrue(rules.contains("<status><code>active</code></status>"));
        assertError("InvalidClientVpnDuplicateAuthorizationRule", "AuthorizeClientVpnIngress",
                params("ClientVpnEndpointId", endpointId, "TargetNetworkCidr", "10.171.0.0/16",
                        "AuthorizeAllGroups", "true"));

        ok("DisassociateClientVpnTargetNetwork", params("ClientVpnEndpointId", endpointId,
                "AssociationId", associationId));
        String deleted = ok("DeleteClientVpnEndpoint", params("ClientVpnEndpointId", endpointId));
        assertTrue(deleted.contains("<status><code>deleting</code></status>"));
        assertError("InvalidClientVpnEndpointId.NotFound", "DescribeClientVpnEndpoints",
                params("ClientVpnEndpointId.1", endpointId));
        assertError("InvalidClientVpnEndpointId.NotFound", "DescribeClientVpnRoutes",
                params("ClientVpnEndpointId", endpointId));
        ok("DeleteSubnet", params("SubnetId", subnetId));
    }

    @Test
    void tagsOnEndpointsAreValidatedAndClassified() {
        assertError("InvalidClientVpnEndpointId.NotFound", "CreateTags", params(
                "ResourceId.1", "cvpn-endpoint-00000000000000000", "Tag.1.Key", "k", "Tag.1.Value", "v"));
        String endpointId = first(ok("CreateClientVpnEndpoint", params(
                "ClientCidrBlock", "172.20.0.0/22",
                "ServerCertificateArn", CERTIFICATE,
                "Authentication.1.Type", "certificate-authentication",
                "Authentication.1.MutualAuthentication.ClientRootCertificateChainArn", CERTIFICATE)),
                "clientVpnEndpointId");

        ok("CreateTags", params("ResourceId.1", endpointId, "Tag.1.Key", "Environment", "Tag.1.Value", "test"));

        String tags = ok("DescribeTags", params("Filter.1.Name", "resource-id", "Filter.1.Value.1", endpointId));
        assertTrue(tags.contains("<resourceType>client-vpn-endpoint</resourceType>"));
        assertTrue(tags.contains("<key>Environment</key>"));
        ok("DeleteTags", params("ResourceId.1", endpointId, "Tag.1.Key", "Environment"));
        assertFalse(ok("DescribeTags", params("Filter.1.Name", "resource-id", "Filter.1.Value.1", endpointId))
                .contains("<key>Environment</key>"));
    }

    @Test
    void createRejectsADeletedVpc() {
        String doomed = ec2.createVpc(REGION, "10.175.0.0/16", false).getVpcId();
        ok("DeleteVpc", params("VpcId", doomed));

        assertError("InvalidVpcID.NotFound", "CreateClientVpnEndpoint", params(
                "ClientCidrBlock", "172.20.0.0/22",
                "ServerCertificateArn", CERTIFICATE,
                "Authentication.1.Type", "certificate-authentication",
                "Authentication.1.MutualAuthentication.ClientRootCertificateChainArn", CERTIFICATE,
                "VpcId", doomed));
    }

    @Test
    void defaultSecurityGroupWireShapeCarriesTheSelfReference() {
        String groupId = defaultGroupId();

        String rules = ok("DescribeSecurityGroupRules", params("Filter.1.Name", "group-id",
                "Filter.1.Value.1", groupId));
        assertTrue(rules.contains("<isEgress>false</isEgress><ipProtocol>-1</ipProtocol>"));
        assertTrue(rules.contains("<referencedGroupInfo><groupId>" + groupId + "</groupId>"));
        assertTrue(rules.contains("<isEgress>true</isEgress><ipProtocol>-1</ipProtocol>"
                + "<cidrIpv4>0.0.0.0/0</cidrIpv4>"));
        String groups = ok("DescribeSecurityGroups", params("GroupId.1", groupId));
        assertTrue(groups.contains("<groupId>" + groupId + "</groupId>"));
        assertError("CannotDelete", "DeleteSecurityGroup", params("GroupId", groupId));
    }

    private String defaultGroupId() {
        return ec2.describeSecurityGroups(REGION, List.of(), List.of("default"), Map.of("vpc-id", List.of(vpcId)))
                .getFirst().getGroupId();
    }

    private String ok(String action, MultivaluedMap<String, String> params) {
        Response response = handler.handle(action, params, REGION);
        String body = String.valueOf(response.getEntity());
        assertEquals(200, response.getStatus(), action + ": " + body);
        return body;
    }

    private void assertError(String code, String action, MultivaluedMap<String, String> params) {
        Response response = handler.handle(action, params, REGION);
        String body = String.valueOf(response.getEntity());
        assertEquals(400, response.getStatus(), action + ": " + body);
        assertTrue(body.contains("<Code>" + code + "</Code>"), action + ": " + body);
    }

    private static MultivaluedMap<String, String> params(String... pairs) {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            params.putSingle(pairs[i], pairs[i + 1]);
        }
        return params;
    }

    private static String first(String xml, String element) {
        Matcher matcher = Pattern.compile("<" + element + ">([^<]+)</" + element + ">").matcher(xml);
        assertTrue(matcher.find(), element + " in " + xml);
        return matcher.group(1);
    }
}
