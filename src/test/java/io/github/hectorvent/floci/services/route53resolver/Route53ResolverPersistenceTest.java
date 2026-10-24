package io.github.hectorvent.floci.services.route53resolver;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.NetworkInterface;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class Route53ResolverPersistenceTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private final ObjectMapper mapper = new ObjectMapper();

    @Inject
    Ec2Service ec2;

    @Test
    void endpointAddressesTagsRulesAndAssociationsSurviveRestartAndDeleteCleanly(@TempDir Path directory) {
        Route53ResolverService service = service(directory);
        String vpcId = ec2.createVpc(REGION, "10.80.0.0/16", false).getVpcId();
        String subnetA = ec2.createSubnet(REGION, vpcId, "10.80.0.0/24", REGION + "a").getSubnetId();
        String subnetB = ec2.createSubnet(REGION, vpcId, "10.80.1.0/24", REGION + "b").getSubnetId();
        String groupId = ec2.describeSecurityGroups(REGION, List.of(), List.of(),
                Map.of("vpc-id", List.of(vpcId))).getFirst().getGroupId();
        try {
            ObjectNode request = mapper.createObjectNode().put("Name", "persisted-endpoint")
                    .put("CreatorRequestId", "persisted-endpoint").put("Direction", "OUTBOUND");
            request.putArray("SecurityGroupIds").add(groupId);
            request.putArray("IpAddresses").addObject().put("SubnetId", subnetA);
            request.withArray("IpAddresses").addObject().put("SubnetId", subnetB);
            request.putArray("Tags").addObject().put("Key", "initial").put("Value", "remove-me");
            ObjectNode endpoint = service.createResolverEndpoint(request, REGION, ACCOUNT);
            String endpointId = endpoint.path("Id").asText();
            String endpointArn = endpoint.path("Arn").asText();
            assertEquals(vpcId, endpoint.path("HostVPCId").asText());
            service.updateResolverEndpoint(endpointId, mapper.createObjectNode()
                    .set("Protocols", mapper.createArrayNode().add("Do53").add("DoH")));
            ObjectNode tags = mapper.createObjectNode().put("ResourceArn", endpointArn);
            tags.putArray("Tags").addObject().put("Key", "team").put("Value", "dns");
            service.tagResource(tags, REGION);
            ObjectNode untag = mapper.createObjectNode().put("ResourceArn", endpointArn);
            untag.putArray("TagKeys").add("initial");
            service.untagResource(untag, REGION);
            ObjectNode ipRequest = mapper.createObjectNode().put("ResolverEndpointId", endpointId);
            JsonNode addresses = service.listResolverEndpointIpAddresses(ipRequest, REGION).path("IpAddresses");
            assertEquals(2, addresses.size());
            assertTrue(addresses.get(0).path("Ip").asText().startsWith("10.80."));
            assertFalse(addresses.get(0).has("_NetworkInterfaceId"));

            ObjectNode ruleRequest = mapper.createObjectNode().put("Name", "persisted-rule")
                    .put("CreatorRequestId", "persisted-rule").put("DomainName", "CORP.EXAMPLE")
                    .put("RuleType", "FORWARD").put("ResolverEndpointId", endpointId);
            ruleRequest.putArray("TargetIps").addObject().put("Ip", "192.168.10.10");
            ruleRequest.putArray("Tags").addObject().put("Key", "owner").put("Value", "dns");
            ObjectNode rule = service.createResolverRule(ruleRequest, REGION, ACCOUNT);
            String ruleId = rule.path("Id").asText();
            ObjectNode associationRequest = mapper.createObjectNode().put("ResolverRuleId", ruleId)
                    .put("VPCId", vpcId);
            ObjectNode association = service.associateResolverRule(associationRequest);
            ObjectNode config = mapper.createObjectNode();
            config.putArray("TargetIps").addObject().put("Ip", "192.168.10.11");
            service.updateResolverRule(ruleId, config);

            Route53ResolverService restarted = service(directory);
            assertEquals(endpointId, restarted.createResolverEndpoint(request, REGION, ACCOUNT).path("Id").asText());
            assertEquals(addresses, restarted.listResolverEndpointIpAddresses(ipRequest, REGION).path("IpAddresses"));
            assertEquals(List.of("Do53", "DoH"), mapper.convertValue(
                    restarted.getResolverEndpoint(endpointId).path("Protocols"), new TypeReference<List<String>>() {}));
            JsonNode persistedTags = restarted.listTagsForResource(tags, REGION).path("Tags");
            assertEquals(1, persistedTags.size());
            assertEquals("team", persistedTags.get(0).path("Key").asText());
            assertEquals("dns", persistedTags.get(0).path("Value").asText());
            assertFalse(restarted.getResolverEndpoint(endpointId).has("_Tags"));
            assertFalse(restarted.getResolverRule(ruleId).has("_Tags"));
            assertEquals("corp.example.", restarted.getResolverRule(ruleId).path("DomainName").asText());
            assertEquals("192.168.10.11", restarted.getResolverRule(ruleId).path("TargetIps").get(0).path("Ip").asText());
            assertEquals(association, restarted.getResolverRuleAssociation(association.path("Id").asText()));
            assertEquals("ResourceInUseException", assertThrows(AwsException.class,
                    () -> restarted.deleteResolverRule(ruleId)).getErrorCode());
            assertEquals("InvalidRequestException", assertThrows(AwsException.class,
                    () -> restarted.deleteResolverEndpoint(endpointId)).getErrorCode());
            restarted.disassociateResolverRule(associationRequest);
            restarted.deleteResolverRule(ruleId);
            restarted.deleteResolverEndpoint(endpointId);

            Route53ResolverService deleted = service(directory);
            assertTrue(deleted.listResolverEndpoints().isEmpty());
            assertTrue(deleted.listResolverRules().isEmpty());
            assertTrue(deleted.listResolverRuleAssociations().isEmpty());
            assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                    () -> deleted.listTagsForResource(tags, REGION)).getErrorCode());
            assertTrue(ec2.describeNetworkInterfaces(REGION, List.of(),
                    Map.of("vpc-id", List.of(vpcId)), 0, null).networkInterfaces().isEmpty());
        } finally {
            for (NetworkInterface networkInterface : ec2.describeNetworkInterfaces(REGION, List.of(),
                    Map.of("vpc-id", List.of(vpcId)), 0, null).networkInterfaces()) {
                ec2.deleteNetworkInterface(REGION, networkInterface.getNetworkInterfaceId());
            }
            ec2.deleteSubnet(REGION, subnetA);
            ec2.deleteSubnet(REGION, subnetB);
            ec2.deleteVpc(REGION, vpcId);
        }
    }

    @Test
    void filteredPaginationAndTagUpdatesSurviveRestart(@TempDir Path directory) {
        Route53ResolverService service = service(directory);
        String[] ids = new String[3];
        for (int index = 0; index < ids.length; index++) {
            ObjectNode request = mapper.createObjectNode().put("Name", "paged-rule-" + index)
                    .put("CreatorRequestId", "paged-rule-" + index).put("RuleType", "SYSTEM")
                    .put("DomainName", "paged-" + index + ".example.");
            ObjectNode rule = service.createResolverRule(request, REGION, ACCOUNT);
            ids[index] = rule.path("Id").asText();
        }
        ObjectNode request = mapper.createObjectNode().put("MaxResults", 1);
        request.putArray("Filters").addObject().put("Name", "Id").putArray("Values").add(ids[0]).add(ids[2]);
        ObjectNode first = service.listResources("ResolverRules", request, REGION, ACCOUNT);
        assertEquals(1, first.path("ResolverRules").size());
        request.put("NextToken", first.path("NextToken").asText());
        Route53ResolverService restarted = service(directory);
        ObjectNode second = restarted.listResources("ResolverRules", request, REGION, ACCOUNT);
        assertEquals(1, second.path("ResolverRules").size());
        assertFalse(second.has("NextToken"));
        assertFalse(first.path("ResolverRules").get(0).equals(second.path("ResolverRules").get(0)));
        assertEquals("InvalidNextTokenException", assertThrows(AwsException.class,
                () -> restarted.listResources("ResolverRules", request, "us-west-2", ACCOUNT)).getErrorCode());
        ObjectNode tagRequest = mapper.createObjectNode().put("ResourceArn",
                restarted.getResolverRule(ids[0]).path("Arn").asText());
        tagRequest.putArray("Tags").addObject().put("Key", "owner").put("Value", "old");
        restarted.tagResource(tagRequest, REGION);
        ((ObjectNode) tagRequest.path("Tags").get(0)).put("Value", "new");
        restarted.tagResource(tagRequest, REGION);
        Route53ResolverService updated = service(directory);
        assertEquals("new", updated.listTagsForResource(tagRequest, REGION).path("Tags").get(0).path("Value").asText());
        for (String id : ids) {
            updated.deleteResolverRule(id);
        }
        assertTrue(service(directory).listResolverRules().isEmpty());
    }

    private Route53ResolverService service(Path directory) {
        return new Route53ResolverService(store(directory, "domains"), store(directory, "endpoints"),
                store(directory, "rules"), store(directory, "associations"), store(directory, "addresses"), mapper, ec2);
    }

    private StorageBackend<String, ObjectNode> store(Path directory, String name) {
        PersistentStorage<String, ObjectNode> store = new PersistentStorage<>(directory.resolve(name + ".json"),
                new TypeReference<Map<String, ObjectNode>>() {});
        store.load();
        return store;
    }
}
