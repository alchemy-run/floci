package io.github.hectorvent.floci.services.route53resolver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.NetworkInterface;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * What {@code CreateResolverEndpoint} does when a stored endpoint has no recorded
 * {@code IpAddressRequests} — a state the public API cannot produce, so it needs the
 * hermetic constructor.
 *
 * <p>The recorded addresses are what a replayed {@code CreatorRequestId} is checked
 * against. Without them the service cannot tell a genuine retry from a different request
 * that happens to reuse the token, so it reports the conflict rather than returning an
 * endpoint that may not match what was asked for. Failing closed is the safe direction:
 * a spurious {@code ResourceExistsException} is loud and recoverable, while a wrong
 * success is silent.</p>
 */
@QuarkusTest
class Route53ResolverReplayFallbackTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private InMemoryStorage<String, ObjectNode> endpointIpRequests;
    private Route53ResolverService service;
    private String vpcId;
    private String subnetId;
    private String groupId;

    @Inject
    Ec2Service ec2;

    @BeforeEach
    void setUp() {
        endpointIpRequests = new InMemoryStorage<>();
        service = new Route53ResolverService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), endpointIpRequests, objectMapper, ec2);
        vpcId = ec2.createVpc(REGION, "10.0.0.0/16", false).getVpcId();
        subnetId = ec2.createSubnet(REGION, vpcId, "10.0.0.0/24", REGION + "a").getSubnetId();
        groupId = ec2.describeSecurityGroups(REGION, List.of(), List.of(),
                Map.of("vpc-id", List.of(vpcId))).getFirst().getGroupId();
    }

    @AfterEach
    void releaseNetwork() {
        for (NetworkInterface networkInterface : ec2.describeNetworkInterfaces(REGION, List.of(),
                Map.of("vpc-id", List.of(vpcId)), 0, null).networkInterfaces()) {
            ec2.deleteNetworkInterface(REGION, networkInterface.getNetworkInterfaceId());
        }
        ec2.deleteSubnet(REGION, subnetId);
        ec2.deleteVpc(REGION, vpcId);
    }

    private JsonNode createRequest(String token, String ip) {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("Name", "ab-fallback");
        request.put("Direction", "INBOUND");
        request.put("CreatorRequestId", token);
        request.putArray("SecurityGroupIds").add(groupId);
        ObjectNode ipRequest = request.putArray("IpAddresses").addObject();
        ipRequest.put("SubnetId", subnetId);
        ipRequest.put("Ip", ip);
        return request;
    }

    @Test
    void replayWithoutARecordedIpRequestIsReportedAsAConflict() {
        String token = "tok-fallback";
        ObjectNode created = service.createResolverEndpoint(createRequest(token, "10.0.0.5"), REGION, ACCOUNT);

        // Drop the record, leaving the endpoint behind: the shape an interrupted write, or a
        // store written by a build predating the record, would leave.
        endpointIpRequests.delete(created.get("Id").asText());

        // Same count, different address. Comparing IpAddressCount alone would call this an
        // equivalent replay and hand back the original endpoint.
        AwsException conflict = assertThrows(AwsException.class,
                () -> service.createResolverEndpoint(createRequest(token, "10.9.9.9"), REGION, ACCOUNT));
        assertEquals("ResourceExistsException", conflict.jsonType());
    }

    @Test
    void replayWithoutARecordedIpRequestConflictsEvenWhenTheRequestIsIdentical() {
        // The cost of failing closed, stated as a test rather than left implicit: with no
        // record there is nothing to compare against, so even a truly identical retry is
        // refused instead of being guessed at.
        String token = "tok-fallback-identical";
        ObjectNode created = service.createResolverEndpoint(createRequest(token, "10.0.0.5"), REGION, ACCOUNT);
        endpointIpRequests.delete(created.get("Id").asText());

        AwsException conflict = assertThrows(AwsException.class,
                () -> service.createResolverEndpoint(createRequest(token, "10.0.0.5"), REGION, ACCOUNT));
        assertEquals("ResourceExistsException", conflict.jsonType());
    }

    @Test
    void anIntactRecordStillReplaysAnIdenticalRequest() {
        // Guard against over-correcting: the ordinary path must stay idempotent.
        String token = "tok-fallback-intact";
        ObjectNode first = service.createResolverEndpoint(createRequest(token, "10.0.0.5"), REGION, ACCOUNT);
        ObjectNode second = service.createResolverEndpoint(createRequest(token, "10.0.0.5"), REGION, ACCOUNT);
        assertEquals(first.get("Id").asText(), second.get("Id").asText());
    }
}
