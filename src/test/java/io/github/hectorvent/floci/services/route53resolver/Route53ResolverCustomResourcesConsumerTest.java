package io.github.hectorvent.floci.services.route53resolver;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Wire-level tests for the custom (non-managed) Route 53 Resolver operations
 * added this session: firewall domain list CRUD, resolver endpoint CRUD,
 * resolver rule CRUD, and resolver rule / VPC associations.
 *
 * <p>These are all new persistence-backed additions layered onto the
 * pre-existing, deterministic-id AWS-managed firewall domain list logic in
 * {@link Route53ResolverService}, which is left untouched (see
 * {@link Route53ResolverIntegrationTest} for its coverage).</p>
 */
@QuarkusTest
class Route53ResolverCustomResourcesConsumerTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/route53resolver/aws4_request";
    /** Same access key (so the same account) in a second region, for region-scoping tests. */
    private static final String AUTH_HEADER_WEST =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-west-2/route53resolver/aws4_request";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Inject
    Ec2Service ec2;

    @Inject
    Route53ResolverService resolver;

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, List<Subnet>> networks = new HashMap<>();
    private final Map<String, String> endpoints = new HashMap<>();

    private Response call(String action, String body) {
        return callAs(AUTH_HEADER, action, body);
    }

    private Response callAs(String authHeader, String action, String body) {
        String region = authHeader.contains("us-west-2") ? "us-west-2" : "us-east-1";
        ObjectNode request = assertDoesNotThrow(() -> (ObjectNode) mapper.readTree(body));
        if ("CreateResolverEndpoint".equals(action) && request.path("IpAddresses").isArray()) {
            List<Subnet> subnets = networks.computeIfAbsent(region, this::createNetwork);
            Map<String, String> subnetIds = Map.of(
                    "subnet-abc", subnets.get(0).getSubnetId(),
                    "subnet-aaa", subnets.get(0).getSubnetId(),
                    "subnet-bbb", subnets.get(1).getSubnetId(),
                    "subnet-ccc", subnets.get(2).getSubnetId(),
                    "subnet-ddd", subnets.get(3).getSubnetId(),
                    "subnet-zzz", subnets.get(2).getSubnetId());
            request.path("IpAddresses").forEach(address -> {
                String subnetId = address.path("SubnetId").asText();
                if (subnetIds.containsKey(subnetId)) {
                    ((ObjectNode) address).put("SubnetId", subnetIds.get(subnetId));
                }
            });
            String groupId = ec2.describeSecurityGroups(region, List.of(), List.of(),
                    Map.of("vpc-id", List.of(subnets.getFirst().getVpcId()))).getFirst().getGroupId();
            request.putArray("SecurityGroupIds").add(groupId);
        }
        Response response = given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "Route53Resolver." + action)
                .header("Authorization", authHeader)
                .body(request.toString())
            .when()
                .post("/");
        if ("CreateResolverEndpoint".equals(action) && response.statusCode() == 200) {
            endpoints.put(response.path("ResolverEndpoint.Id"), region);
        }
        if ("DeleteResolverEndpoint".equals(action) && response.statusCode() == 200) {
            endpoints.remove(request.path("ResolverEndpointId").asText());
        }
        return response;
    }

    private List<Subnet> createNetwork(String region) {
        String vpcId = ec2.createVpc(region, "10.0.0.0/16", false).getVpcId();
        List<Subnet> subnets = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            subnets.add(ec2.createSubnet(region, vpcId, "10.0." + index + ".0/24",
                    region + (index % 2 == 0 ? "a" : "b")));
        }
        return subnets;
    }

    @AfterEach
    void releaseEndpointNetworks() {
        endpoints.keySet().forEach(resolver::deleteResolverEndpoint);
        networks.forEach((region, subnets) -> {
            subnets.forEach(subnet -> ec2.deleteSubnet(region, subnet.getSubnetId()));
            ec2.deleteVpc(region, subnets.getFirst().getVpcId());
        });
    }

    // ---------- CreateFirewallDomainList / DeleteFirewallDomainList ----------

    @Test
    void createFirewallDomainList_returnsCompleteListAndIsListable() {
        String id = call("CreateFirewallDomainList", "{\"Name\":\"ab-custom-fdl\","
                + "\"CreatorRequestId\":\"tok-create-fdl\"}")
        .then()
            .statusCode(200)
            .body("FirewallDomainList.Name", equalTo("ab-custom-fdl"))
            .body("FirewallDomainList.Status", equalTo("COMPLETE"))
            .extract().path("FirewallDomainList.Id");

        call("GetFirewallDomainList", "{\"FirewallDomainListId\":\"" + id + "\"}")
        .then()
            .statusCode(200)
            .body("FirewallDomainList.Id", equalTo(id));

        call("ListFirewallDomainLists", "{}")
        .then()
            .statusCode(200)
            .body("FirewallDomainLists.Id", hasItem(id));
    }

    @Test
    void deleteFirewallDomainList_removesCustomList() {
        String id = call("CreateFirewallDomainList", "{\"Name\":\"ab-delete-fdl\","
                + "\"CreatorRequestId\":\"tok-delete-fdl\"}")
        .then().statusCode(200)
        .extract().path("FirewallDomainList.Id");

        call("DeleteFirewallDomainList", "{\"FirewallDomainListId\":\"" + id + "\"}")
        .then()
            .statusCode(200)
            .body("FirewallDomainList.Status", equalTo("DELETING"));

        call("GetFirewallDomainList", "{\"FirewallDomainListId\":\"" + id + "\"}")
        .then()
            .statusCode(404);
    }

    @Test
    void createFirewallDomainList_missingName_returnsValidationException() {
        // The DNS Firewall operations model ValidationException, not the resolver
        // family's InvalidParameterException: CreateFirewallDomainList does not list
        // InvalidParameterException among its errors at all.
        call("CreateFirewallDomainList", "{\"CreatorRequestId\":\"tok-fdl-noname\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    // ---------- CreatorRequestId idempotency ----------

    @Test
    void createFirewallDomainList_replayedCreatorRequestId_returnsOriginalList() {
        String body = "{\"Name\":\"ab-idem-fdl\",\"CreatorRequestId\":\"tok-idem-fdl\"}";

        String first = call("CreateFirewallDomainList", body)
                .then().statusCode(200).extract().path("FirewallDomainList.Id");
        String second = call("CreateFirewallDomainList", body)
                .then().statusCode(200).extract().path("FirewallDomainList.Id");

        assertEquals(first, second);
        call("ListFirewallDomainLists", "{}")
        .then()
            .statusCode(200)
            .body("FirewallDomainLists.findAll { it.CreatorRequestId == 'tok-idem-fdl' }.size()",
                    equalTo(1));
    }

    @Test
    void createFirewallDomainList_withoutCreatorRequestId_createsDistinctLists() {
        String body = "{\"Name\":\"ab-noidem-fdl\"}";

        String first = call("CreateFirewallDomainList", body)
                .then().statusCode(200).extract().path("FirewallDomainList.Id");
        String second = call("CreateFirewallDomainList", body)
                .then().statusCode(200).extract().path("FirewallDomainList.Id");

        assertNotEquals(first, second);
    }

    @Test
    void createResolverEndpoint_replayedCreatorRequestId_returnsOriginalEndpoint() {
        String body = "{\"Name\":\"ab-idem-endpoint\",\"Direction\":\"INBOUND\","
                + "\"SecurityGroupIds\":[\"sg-abc123\"],\"IpAddresses\":[{\"SubnetId\":\"subnet-abc\","
                + "\"Ip\":\"10.0.0.5\"}],\"CreatorRequestId\":\"tok-idem-endpoint\"}";

        String first = call("CreateResolverEndpoint", body)
                .then().statusCode(200).extract().path("ResolverEndpoint.Id");
        String second = call("CreateResolverEndpoint", body)
                .then().statusCode(200).extract().path("ResolverEndpoint.Id");

        assertEquals(first, second);
        call("ListResolverEndpoints", "{}")
        .then()
            .statusCode(200)
            .body("ResolverEndpoints.findAll { it.CreatorRequestId == 'tok-idem-endpoint' }.size()",
                    equalTo(1));
    }

    @Test
    void createResolverEndpoint_withoutCreatorRequestId_createsDistinctEndpoints() {
        String body = "{\"Name\":\"ab-noidem-endpoint\",\"Direction\":\"INBOUND\","
                + "\"SecurityGroupIds\":[\"sg-abc123\"],\"IpAddresses\":[{\"SubnetId\":\"subnet-abc\"}]}";

        String first = call("CreateResolverEndpoint", body)
                .then().statusCode(200).extract().path("ResolverEndpoint.Id");
        String second = call("CreateResolverEndpoint", body)
                .then().statusCode(200).extract().path("ResolverEndpoint.Id");

        assertNotEquals(first, second);
    }

    @Test
    void createResolverEndpoint_replayedCreatorRequestIdWithInvalidBody_stillValidates() {
        String valid = "{\"Name\":\"ab-idem-validate\",\"Direction\":\"INBOUND\","
                + "\"SecurityGroupIds\":[\"sg-abc123\"],\"IpAddresses\":[{\"SubnetId\":\"subnet-abc\","
                + "\"Ip\":\"10.0.0.5\"}],\"CreatorRequestId\":\"tok-idem-validate\"}";
        call("CreateResolverEndpoint", valid).then().statusCode(200);

        // Replaying the token does not excuse a malformed request body.
        call("CreateResolverEndpoint", "{\"Name\":\"ab-idem-validate\",\"Direction\":\"INBOUND\","
                + "\"CreatorRequestId\":\"tok-idem-validate\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParameterException"));

        // ... including an unrecognised Direction.
        call("CreateResolverEndpoint", "{\"Name\":\"ab-idem-validate\",\"Direction\":\"SIDEWAYS\","
                + "\"SecurityGroupIds\":[\"sg-abc123\"],\"IpAddresses\":[{\"SubnetId\":\"subnet-abc\","
                + "\"Ip\":\"10.0.0.5\"}],\"CreatorRequestId\":\"tok-idem-validate\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParameterException"));
    }

    @Test
    void createResolverRule_replayedCreatorRequestId_returnsOriginalRule() {
        String body = "{\"Name\":\"ab-idem-rule\",\"RuleType\":\"FORWARD\","
                + "\"DomainName\":\"ab-idem-rule.example.com.\","
                + "\"TargetIps\":[{\"Ip\":\"10.0.0.1\",\"Port\":53}],"
                + "\"CreatorRequestId\":\"tok-idem-rule\"}";

        String first = call("CreateResolverRule", body)
                .then().statusCode(200).extract().path("ResolverRule.Id");
        String second = call("CreateResolverRule", body)
                .then().statusCode(200).extract().path("ResolverRule.Id");

        assertEquals(first, second);
        call("ListResolverRules", "{}")
        .then()
            .statusCode(200)
            .body("ResolverRules.findAll { it.CreatorRequestId == 'tok-idem-rule' }.size()", equalTo(1));
    }

    @Test
    void createResolverRule_withoutCreatorRequestId_createsDistinctRules() {
        String body = "{\"Name\":\"ab-noidem-rule\",\"RuleType\":\"FORWARD\","
                + "\"DomainName\":\"ab-noidem-rule.example.com.\","
                + "\"TargetIps\":[{\"Ip\":\"10.0.0.1\",\"Port\":53}]}";

        String first = call("CreateResolverRule", body)
                .then().statusCode(200).extract().path("ResolverRule.Id");
        String second = call("CreateResolverRule", body)
                .then().statusCode(200).extract().path("ResolverRule.Id");

        assertNotEquals(first, second);
    }

    // ---------- A replayed token with different parameters is a conflict ----------

    @Test
    void createResolverEndpoint_replayedCreatorRequestIdWithDifferentParameters_returnsResourceExists() {
        String token = "tok-conflict-endpoint";
        call("CreateResolverEndpoint", "{\"Name\":\"ab-conflict-endpoint\",\"Direction\":\"INBOUND\","
                + "\"SecurityGroupIds\":[\"sg-abc123\"],\"IpAddresses\":[{\"SubnetId\":\"subnet-abc\","
                + "\"Ip\":\"10.0.0.5\"}],\"CreatorRequestId\":\"" + token + "\"}")
        .then().statusCode(200);

        // Same token, same region, different (but individually valid) Name: AWS models
        // ResourceExistsException for this rather than silently returning the original.
        call("CreateResolverEndpoint", "{\"Name\":\"ab-conflict-endpoint-renamed\",\"Direction\":\"INBOUND\","
                + "\"SecurityGroupIds\":[\"sg-abc123\"],\"IpAddresses\":[{\"SubnetId\":\"subnet-abc\","
                + "\"Ip\":\"10.0.0.5\"}],\"CreatorRequestId\":\"" + token + "\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceExistsException"));
    }

    @Test
    void createResolverEndpoint_replayedCreatorRequestIdWithDifferentIpValues_returnsResourceExists() {
        String token = "tok-conflict-endpoint-ips";
        call("CreateResolverEndpoint", "{\"Name\":\"ab-conflict-ips\",\"Direction\":\"INBOUND\","
                + "\"SecurityGroupIds\":[\"sg-abc123\"],\"IpAddresses\":["
                + "{\"SubnetId\":\"subnet-aaa\",\"Ip\":\"10.0.0.5\"},"
                + "{\"SubnetId\":\"subnet-bbb\",\"Ip\":\"10.0.1.5\"}],"
                + "\"CreatorRequestId\":\"" + token + "\"}")
        .then().statusCode(200);

        // Same COUNT of IP requests, different subnet and IP values. Comparing counts alone
        // would read this as an equivalent replay and silently return the original endpoint.
        call("CreateResolverEndpoint", "{\"Name\":\"ab-conflict-ips\",\"Direction\":\"INBOUND\","
                + "\"SecurityGroupIds\":[\"sg-abc123\"],\"IpAddresses\":["
                + "{\"SubnetId\":\"subnet-ccc\",\"Ip\":\"10.0.2.5\"},"
                + "{\"SubnetId\":\"subnet-ddd\",\"Ip\":\"10.0.3.5\"}],"
                + "\"CreatorRequestId\":\"" + token + "\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceExistsException"));
    }

    @Test
    void createResolverEndpoint_replayedCreatorRequestIdWithIdenticalIps_returnsTheOriginal() {
        String token = "tok-replay-endpoint-ips";
        String body = "{\"Name\":\"ab-replay-ips\",\"Direction\":\"INBOUND\","
                + "\"SecurityGroupIds\":[\"sg-abc123\"],\"IpAddresses\":["
                + "{\"SubnetId\":\"subnet-aaa\",\"Ip\":\"10.0.0.5\"},"
                + "{\"SubnetId\":\"subnet-bbb\",\"Ip\":\"10.0.1.5\"}],"
                + "\"CreatorRequestId\":\"" + token + "\"}";

        String first = call("CreateResolverEndpoint", body)
                .then().statusCode(200).extract().path("ResolverEndpoint.Id");

        // A genuine retry must still be idempotent, the stricter comparison must not turn
        // every retry into a conflict.
        call("CreateResolverEndpoint", body)
        .then()
            .statusCode(200)
            .body("ResolverEndpoint.Id", equalTo(first));
    }

    @Test
    void createResolverEndpoint_replayedWithReorderedFieldsWithinIpRequests_returnsTheOriginal() {
        // Same two IP requests, but the members are written in a different order inside each
        // object. JSON object member order is not significant, so this is the same request.
        String token = "tok-field-order";
        String subnetFirst = "{\"Name\":\"ab-field-order\",\"Direction\":\"INBOUND\","
                + "\"SecurityGroupIds\":[\"sg-abc123\"],\"IpAddresses\":["
                + "{\"SubnetId\":\"subnet-zzz\",\"Ip\":\"10.0.2.5\"},"
                + "{\"SubnetId\":\"subnet-aaa\",\"Ip\":\"10.0.0.9\"}],"
                + "\"CreatorRequestId\":\"" + token + "\"}";
        String ipFirst = "{\"Name\":\"ab-field-order\",\"Direction\":\"INBOUND\","
                + "\"SecurityGroupIds\":[\"sg-abc123\"],\"IpAddresses\":["
                + "{\"Ip\":\"10.0.2.5\",\"SubnetId\":\"subnet-zzz\"},"
                + "{\"Ip\":\"10.0.0.9\",\"SubnetId\":\"subnet-aaa\"}],"
                + "\"CreatorRequestId\":\"" + token + "\"}";

        String first = call("CreateResolverEndpoint", subnetFirst)
                .then().statusCode(200).extract().path("ResolverEndpoint.Id");

        call("CreateResolverEndpoint", ipFirst)
        .then()
            .statusCode(200)
            .body("ResolverEndpoint.Id", equalTo(first));
    }

    @Test
    void createResolverEndpoint_replayResponseOmitsInternalIpFingerprint() {
        String token = "tok-replay-endpoint-nofingerprint";
        call("CreateResolverEndpoint", "{\"Name\":\"ab-nofingerprint\",\"Direction\":\"INBOUND\","
                + "\"SecurityGroupIds\":[\"sg-abc123\"],\"IpAddresses\":[{\"SubnetId\":\"subnet-aaa\","
                + "\"Ip\":\"10.0.0.5\"}],\"CreatorRequestId\":\"" + token + "\"}")
        .then()
            .statusCode(200)
            // ResolverEndpoint models IpAddressCount but no IP list, so whatever we retain to
            // detect a changed IP set must not reach the wire.
            .body("ResolverEndpoint.IpAddressCount", equalTo(1))
            .body("ResolverEndpoint.any { it.key == 'IpAddressRequests' }", equalTo(false))
            .body("ResolverEndpoint.any { it.key == 'IpAddresses' }", equalTo(false))
            .body("ResolverEndpoint.any { it.key == '_Tags' }", equalTo(false));
    }

    @Test
    void createResolverRule_replayedCreatorRequestIdWithDifferentParameters_returnsResourceExists() {
        String token = "tok-conflict-rule";
        call("CreateResolverRule", "{\"Name\":\"ab-conflict-rule\",\"RuleType\":\"FORWARD\","
                + "\"DomainName\":\"ab-conflict.example.com.\","
                + "\"TargetIps\":[{\"Ip\":\"10.0.0.1\",\"Port\":53}],"
                + "\"CreatorRequestId\":\"" + token + "\"}")
        .then().statusCode(200);

        call("CreateResolverRule", "{\"Name\":\"ab-conflict-rule\",\"RuleType\":\"FORWARD\","
                + "\"DomainName\":\"ab-conflict-different.example.com.\","
                + "\"TargetIps\":[{\"Ip\":\"10.0.0.1\",\"Port\":53}],"
                + "\"CreatorRequestId\":\"" + token + "\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceExistsException"));
    }

    @Test
    void createFirewallDomainList_replayedCreatorRequestIdWithDifferentParameters_returnsTheOriginal() {
        // Deliberate divergence, pinned so it cannot drift silently: CreateFirewallDomainList
        // models no conflict error at all (LimitExceeded / Validation / AccessDenied /
        // InternalServiceError / Throttling only), so there is no faithful way to report a
        // conflicting retry here. The lenient replay stands rather than inventing an error
        // code. See issues/route53resolver-firewall-domain-list-retry-conflict.md.
        String token = "tok-conflict-fdl";
        String first = call("CreateFirewallDomainList",
                "{\"Name\":\"ab-conflict-fdl\",\"CreatorRequestId\":\"" + token + "\"}")
                .then().statusCode(200).extract().path("FirewallDomainList.Id");

        call("CreateFirewallDomainList",
                "{\"Name\":\"ab-conflict-fdl-renamed\",\"CreatorRequestId\":\"" + token + "\"}")
        .then()
            .statusCode(200)
            .body("FirewallDomainList.Id", equalTo(first))
            .body("FirewallDomainList.Name", equalTo("ab-conflict-fdl"));
    }

    // ---------- CreatorRequestId replay is scoped to one region ----------

    @Test
    void createFirewallDomainList_sameCreatorRequestIdInAnotherRegion_createsRegionalList() {
        String body = "{\"Name\":\"ab-xregion-fdl\",\"CreatorRequestId\":\"tok-xregion-fdl\"}";

        String east = callAs(AUTH_HEADER, "CreateFirewallDomainList", body)
                .then().statusCode(200)
                .body("FirewallDomainList.Arn", startsWith("arn:aws:route53resolver:us-east-1:"))
                .extract().path("FirewallDomainList.Id");

        callAs(AUTH_HEADER_WEST, "CreateFirewallDomainList", body)
        .then()
            .statusCode(200)
            // A replay in another region must not hand back us-east-1's resource.
            .body("FirewallDomainList.Id", not(equalTo(east)))
            .body("FirewallDomainList.Arn", startsWith("arn:aws:route53resolver:us-west-2:"));
    }

    @Test
    void createResolverEndpoint_sameCreatorRequestIdInAnotherRegion_createsRegionalEndpoint() {
        String body = "{\"Name\":\"ab-xregion-endpoint\",\"Direction\":\"INBOUND\","
                + "\"SecurityGroupIds\":[\"sg-abc123\"],\"IpAddresses\":[{\"SubnetId\":\"subnet-abc\","
                + "\"Ip\":\"10.0.0.5\"}],\"CreatorRequestId\":\"tok-xregion-endpoint\"}";

        String east = callAs(AUTH_HEADER, "CreateResolverEndpoint", body)
                .then().statusCode(200)
                .body("ResolverEndpoint.Arn", startsWith("arn:aws:route53resolver:us-east-1:"))
                .extract().path("ResolverEndpoint.Id");

        callAs(AUTH_HEADER_WEST, "CreateResolverEndpoint", body)
        .then()
            .statusCode(200)
            .body("ResolverEndpoint.Id", not(equalTo(east)))
            .body("ResolverEndpoint.Arn", startsWith("arn:aws:route53resolver:us-west-2:"));
    }

    @Test
    void createResolverRule_sameCreatorRequestIdInAnotherRegion_createsRegionalRule() {
        String body = "{\"Name\":\"ab-xregion-rule\",\"RuleType\":\"FORWARD\","
                + "\"DomainName\":\"ab-xregion-rule.example.com.\","
                + "\"TargetIps\":[{\"Ip\":\"10.0.0.1\",\"Port\":53}],"
                + "\"CreatorRequestId\":\"tok-xregion-rule\"}";

        String east = callAs(AUTH_HEADER, "CreateResolverRule", body)
                .then().statusCode(200)
                .body("ResolverRule.Arn", startsWith("arn:aws:route53resolver:us-east-1:"))
                .extract().path("ResolverRule.Id");

        callAs(AUTH_HEADER_WEST, "CreateResolverRule", body)
        .then()
            .statusCode(200)
            .body("ResolverRule.Id", not(equalTo(east)))
            .body("ResolverRule.Arn", startsWith("arn:aws:route53resolver:us-west-2:"));
    }

    @Test
    void createFirewallDomainList_replayWithinTheSameRegion_stillReturnsTheOriginal() {
        String body = "{\"Name\":\"ab-sameregion-fdl\",\"CreatorRequestId\":\"tok-sameregion-fdl\"}";

        String first = callAs(AUTH_HEADER_WEST, "CreateFirewallDomainList", body)
                .then().statusCode(200).extract().path("FirewallDomainList.Id");
        String second = callAs(AUTH_HEADER_WEST, "CreateFirewallDomainList", body)
                .then().statusCode(200).extract().path("FirewallDomainList.Id");

        assertEquals(first, second);
    }

    // ---------- Resolver endpoints ----------

    private String createEndpoint(String name) {
        return call("CreateResolverEndpoint", "{\"Name\":\"" + name + "\",\"Direction\":\"INBOUND\","
                + "\"SecurityGroupIds\":[\"sg-abc123\"],\"IpAddresses\":[{\"SubnetId\":\"subnet-abc\","
                + "\"Ip\":\"10.0.0.5\"}],\"CreatorRequestId\":\"tok-" + name + "\"}")
        .then().statusCode(200)
        .extract().path("ResolverEndpoint.Id");
    }

    @Test
    void createResolverEndpoint_returnsOperationalEndpoint() {
        call("CreateResolverEndpoint", "{\"Name\":\"ab-endpoint-create\",\"Direction\":\"INBOUND\","
                + "\"SecurityGroupIds\":[\"sg-abc123\"],\"IpAddresses\":[{\"SubnetId\":\"subnet-abc\","
                + "\"Ip\":\"10.0.0.5\"}],\"CreatorRequestId\":\"tok-endpoint-create\"}")
        .then()
            .statusCode(200)
            .body("ResolverEndpoint.Name", equalTo("ab-endpoint-create"))
            .body("ResolverEndpoint.Direction", equalTo("INBOUND"))
            .body("ResolverEndpoint.Status", equalTo("OPERATIONAL"))
            .body("ResolverEndpoint.IpAddressCount", equalTo(1));
    }

    @Test
    void createResolverEndpoint_inboundGetsInboundIdPrefix() {
        call("CreateResolverEndpoint", "{\"Name\":\"ab-endpoint-inbound\",\"Direction\":\"INBOUND\","
                + "\"SecurityGroupIds\":[\"sg-abc123\"],\"IpAddresses\":[{\"SubnetId\":\"subnet-abc\","
                + "\"Ip\":\"10.0.0.5\"}],\"CreatorRequestId\":\"tok-endpoint-inbound\"}")
        .then()
            .statusCode(200)
            .body("ResolverEndpoint.Direction", equalTo("INBOUND"))
            .body("ResolverEndpoint.Id", startsWith("rslvr-in-"));
    }

    @Test
    void createResolverEndpoint_outboundGetsOutboundIdPrefix() {
        call("CreateResolverEndpoint", "{\"Name\":\"ab-endpoint-outbound\",\"Direction\":\"OUTBOUND\","
                + "\"SecurityGroupIds\":[\"sg-abc123\"],\"IpAddresses\":[{\"SubnetId\":\"subnet-abc\","
                + "\"Ip\":\"10.0.0.6\"}],\"CreatorRequestId\":\"tok-endpoint-outbound\"}")
        .then()
            .statusCode(200)
            .body("ResolverEndpoint.Direction", equalTo("OUTBOUND"))
            .body("ResolverEndpoint.Id", startsWith("rslvr-out-"));
    }

    @Test
    void createResolverEndpoint_unknownDirection_returnsInvalidParameters() {
        call("CreateResolverEndpoint", "{\"Name\":\"ab-endpoint-sideways\",\"Direction\":\"SIDEWAYS\","
                + "\"SecurityGroupIds\":[\"sg-abc123\"],\"IpAddresses\":[{\"SubnetId\":\"subnet-abc\","
                + "\"Ip\":\"10.0.0.7\"}],\"CreatorRequestId\":\"tok-endpoint-sideways\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParameterException"));
    }

    @Test
    void createResolverEndpoint_missingIpAddresses_returnsInvalidParameters() {
        call("CreateResolverEndpoint", "{\"Name\":\"ab-endpoint-noip\",\"Direction\":\"INBOUND\","
                + "\"SecurityGroupIds\":[\"sg-abc123\"],\"CreatorRequestId\":\"tok-noip\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParameterException"));
    }

    @Test
    void getResolverEndpoint_returnsCreatedEndpoint() {
        String id = createEndpoint("ab-endpoint-get");

        call("GetResolverEndpoint", "{\"ResolverEndpointId\":\"" + id + "\"}")
        .then()
            .statusCode(200)
            .body("ResolverEndpoint.Id", equalTo(id));
    }

    @Test
    void getResolverEndpoint_unknownId_returnsResourceNotFound() {
        call("GetResolverEndpoint", "{\"ResolverEndpointId\":\"rslvr-in-doesnotexist\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void listResolverEndpoints_includesCreatedEndpoint() {
        String id = createEndpoint("ab-endpoint-list");

        call("ListResolverEndpoints", "{}")
        .then()
            .statusCode(200)
            .body("ResolverEndpoints.Id", hasItem(id));
    }

    @Test
    void updateResolverEndpoint_changesName() {
        String id = createEndpoint("ab-endpoint-update-before");

        call("UpdateResolverEndpoint", "{\"ResolverEndpointId\":\"" + id
                + "\",\"Name\":\"ab-endpoint-update-after\"}")
        .then()
            .statusCode(200)
            .body("ResolverEndpoint.Id", equalTo(id))
            .body("ResolverEndpoint.Name", equalTo("ab-endpoint-update-after"));
    }

    @Test
    void updateResolverEndpoint_unknownEndpointType_returnsInvalidParameters() {
        String id = createEndpoint("ab-endpoint-badtype");

        // ResolverEndpointType is an enum of IPV6 / IPV4 / DUALSTACK: storing anything
        // else would have GetResolverEndpoint report an endpoint type AWS never allows.
        call("UpdateResolverEndpoint", "{\"ResolverEndpointId\":\"" + id
                + "\",\"ResolverEndpointType\":\"IPV5\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParameterException"));

        call("GetResolverEndpoint", "{\"ResolverEndpointId\":\"" + id + "\"}")
        .then()
            .statusCode(200)
            .body("ResolverEndpoint.ResolverEndpointType", equalTo("IPV4"));
    }

    @Test
    void deleteResolverEndpoint_removesEndpoint() {
        String id = createEndpoint("ab-endpoint-delete");

        call("DeleteResolverEndpoint", "{\"ResolverEndpointId\":\"" + id + "\"}")
        .then()
            .statusCode(200)
            .body("ResolverEndpoint.Status", equalTo("DELETING"));

        call("GetResolverEndpoint", "{\"ResolverEndpointId\":\"" + id + "\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    // ---------- Resolver rules ----------

    private String createRule(String name, String domain) {
        return call("CreateResolverRule", "{\"Name\":\"" + name + "\",\"RuleType\":\"FORWARD\","
                + "\"DomainName\":\"" + domain + "\",\"TargetIps\":[{\"Ip\":\"10.0.0.1\",\"Port\":53}],"
                + "\"CreatorRequestId\":\"tok-" + name + "\"}")
        .then().statusCode(200)
        .extract().path("ResolverRule.Id");
    }

    @Test
    void createResolverRule_returnsCompleteRule() {
        call("CreateResolverRule", "{\"Name\":\"ab-rule-create\",\"RuleType\":\"FORWARD\","
                + "\"DomainName\":\"example.com.\",\"TargetIps\":[{\"Ip\":\"10.0.0.1\",\"Port\":53}],"
                + "\"CreatorRequestId\":\"tok-rule-create\"}")
        .then()
            .statusCode(200)
            .body("ResolverRule.Name", equalTo("ab-rule-create"))
            .body("ResolverRule.DomainName", equalTo("example.com."))
            .body("ResolverRule.RuleType", equalTo("FORWARD"))
            .body("ResolverRule.Status", equalTo("COMPLETE"));
    }

    @Test
    void createResolverRule_emptyTargetIps_returnsInvalidParameters() {
        // The model pins TargetIps to list min 1: present-but-empty is invalid.
        // Absent TargetIps stays allowed (SYSTEM rules carry none).
        call("CreateResolverRule", "{\"Name\":\"ab-rule-emptytargets\",\"RuleType\":\"FORWARD\","
                + "\"DomainName\":\"ab-rule-emptytargets.example.com.\",\"TargetIps\":[],"
                + "\"CreatorRequestId\":\"tok-rule-emptytargets\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParameterException"));
    }

    @Test
    void createResolverRule_unknownRuleType_returnsInvalidParameters() {
        // RuleTypeOption is FORWARD / SYSTEM / RECURSIVE / DELEGATE. An unmodelled value
        // was stored verbatim and echoed back by Get/ListResolverRules as a real rule.
        call("CreateResolverRule", "{\"Name\":\"ab-rule-badtype\",\"RuleType\":\"SIDEWAYS\","
                + "\"DomainName\":\"ab-rule-badtype.example.com.\","
                + "\"TargetIps\":[{\"Ip\":\"10.0.0.1\",\"Port\":53}],"
                + "\"CreatorRequestId\":\"tok-rule-badtype\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParameterException"));

        call("ListResolverRules", "{}")
        .then()
            .statusCode(200)
            .body("ResolverRules.Name", not(hasItem("ab-rule-badtype")));
    }

    @Test
    void getResolverRule_returnsCreatedRule() {
        String id = createRule("ab-rule-get", "ab-rule-get.example.com.");

        call("GetResolverRule", "{\"ResolverRuleId\":\"" + id + "\"}")
        .then()
            .statusCode(200)
            .body("ResolverRule.Id", equalTo(id));
    }

    @Test
    void getResolverRule_unknownId_returnsResourceNotFound() {
        call("GetResolverRule", "{\"ResolverRuleId\":\"rslvr-rr-doesnotexist\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void listResolverRules_includesCreatedRule() {
        String id = createRule("ab-rule-list", "ab-rule-list.example.com.");

        call("ListResolverRules", "{}")
        .then()
            .statusCode(200)
            .body("ResolverRules.Id", hasItem(id));
    }

    @Test
    void updateResolverRule_changesName() {
        String id = createRule("ab-rule-update-before", "ab-rule-update.example.com.");

        call("UpdateResolverRule", "{\"ResolverRuleId\":\"" + id
                + "\",\"Config\":{\"Name\":\"ab-rule-update-after\"}}")
        .then()
            .statusCode(200)
            .body("ResolverRule.Id", equalTo(id))
            .body("ResolverRule.Name", equalTo("ab-rule-update-after"));
    }

    @Test
    void deleteResolverRule_removesRule() {
        String id = createRule("ab-rule-delete", "ab-rule-delete.example.com.");

        call("DeleteResolverRule", "{\"ResolverRuleId\":\"" + id + "\"}")
        .then()
            .statusCode(200)
            .body("ResolverRule.Status", equalTo("DELETING"));

        call("GetResolverRule", "{\"ResolverRuleId\":\"" + id + "\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    // ---------- Resolver rule associations ----------

    @Test
    void associateResolverRule_returnsCompleteAssociation() {
        String ruleId = createRule("ab-assoc-rule", "ab-assoc.example.com.");

        String associationId = call("AssociateResolverRule", "{\"ResolverRuleId\":\"" + ruleId
                + "\",\"VPCId\":\"vpc-abc123\",\"Name\":\"ab-assoc-name\"}")
        .then()
            .statusCode(200)
            .body("ResolverRuleAssociation.ResolverRuleId", equalTo(ruleId))
            .body("ResolverRuleAssociation.VPCId", equalTo("vpc-abc123"))
            .body("ResolverRuleAssociation.Status", equalTo("COMPLETE"))
            .extract().path("ResolverRuleAssociation.Id");

        call("GetResolverRuleAssociation", "{\"ResolverRuleAssociationId\":\"" + associationId + "\"}")
        .then()
            .statusCode(200)
            .body("ResolverRuleAssociation.Id", equalTo(associationId));
    }

    @Test
    void associateResolverRule_unknownRule_returnsResourceNotFound() {
        call("AssociateResolverRule", "{\"ResolverRuleId\":\"rslvr-rr-doesnotexist\","
                + "\"VPCId\":\"vpc-abc123\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void listResolverRuleAssociations_includesAssociatedRule() {
        String ruleId = createRule("ab-list-assoc-rule", "ab-list-assoc.example.com.");
        call("AssociateResolverRule", "{\"ResolverRuleId\":\"" + ruleId
                + "\",\"VPCId\":\"vpc-list-assoc\"}")
        .then().statusCode(200);

        call("ListResolverRuleAssociations", "{}")
        .then()
            .statusCode(200)
            .body("ResolverRuleAssociations.ResolverRuleId", hasItem(ruleId));
    }

    @Test
    void disassociateResolverRule_removesAssociation() {
        String ruleId = createRule("ab-disassoc-rule", "ab-disassoc.example.com.");
        call("AssociateResolverRule", "{\"ResolverRuleId\":\"" + ruleId
                + "\",\"VPCId\":\"vpc-disassoc\"}")
        .then().statusCode(200);

        call("DisassociateResolverRule", "{\"ResolverRuleId\":\"" + ruleId
                + "\",\"VPCId\":\"vpc-disassoc\"}")
        .then()
            .statusCode(200)
            .body("ResolverRuleAssociation.Status", equalTo("DELETING"));

        call("ListResolverRuleAssociations", "{}")
        .then()
            .statusCode(200)
            .body("ResolverRuleAssociations.findAll { it.ResolverRuleId == '" + ruleId + "' }.size()",
                    equalTo(0));
    }

    @Test
    void endpointUsesAwsIpAddressesAndPersistsProtocolsAndTagsWithoutLeakingMetadata() {
        Response created = call("CreateResolverEndpoint", """
                {"Name":"resolver-wire","CreatorRequestId":"resolver-wire","Direction":"INBOUND",
                 "IpAddresses":[{"SubnetId":"subnet-aaa"},{"SubnetId":"subnet-bbb"}],
                 "Tags":[{"Key":"owner","Value":"initial"}],"Protocols":["Do53"]}
                """);
        String id = created.then().statusCode(200)
                .body("ResolverEndpoint.IpAddressCount", equalTo(2))
                .body("ResolverEndpoint.ResolverEndpointType", equalTo("IPV4"))
                .body("ResolverEndpoint.HostVPCId", equalTo(networks.get("us-east-1").getFirst().getVpcId()))
                .body("ResolverEndpoint.containsKey('_Tags')", equalTo(false))
                .extract().path("ResolverEndpoint.Id");
        String arn = created.path("ResolverEndpoint.Arn");
        String resource = "{\"ResourceArn\":\"" + arn + "\"}";
        call("ListTagsForResource", resource).then().statusCode(200)
                .body("Tags", equalTo(List.of(Map.of("Key", "owner", "Value", "initial"))));
        call("TagResource", "{\"ResourceArn\":\"" + arn
                + "\",\"Tags\":[{\"Key\":\"owner\",\"Value\":\"updated\"},"
                + "{\"Key\":\"team\",\"Value\":\"dns\"}]}").then().statusCode(200);
        call("UntagResource", "{\"ResourceArn\":\"" + arn + "\",\"TagKeys\":[\"owner\",\"absent\"]}")
                .then().statusCode(200);
        call("ListTagsForResource", resource).then().statusCode(200)
                .body("Tags", equalTo(List.of(Map.of("Key", "team", "Value", "dns"))));
        call("UpdateResolverEndpoint", "{\"ResolverEndpointId\":\"" + id
                + "\",\"Protocols\":[\"Do53\",\"DoH\"]}").then().statusCode(200)
                .body("ResolverEndpoint.Protocols", equalTo(List.of("Do53", "DoH")));
        call("GetResolverEndpoint", "{\"ResolverEndpointId\":\"" + id + "\"}").then().statusCode(200)
                .body("ResolverEndpoint.Protocols", equalTo(List.of("Do53", "DoH")));
        Response firstPage = call("ListResolverEndpointIpAddresses", "{\"ResolverEndpointId\":\"" + id
                + "\",\"MaxResults\":1}");
        String nextToken = firstPage.then().statusCode(200).body("IpAddresses.size()", equalTo(1))
                .body("IpAddresses[0].Status", equalTo("ATTACHED"))
                .body("IpAddresses[0].containsKey('_NetworkInterfaceId')", equalTo(false))
                .extract().path("NextToken");
        String firstIp = firstPage.path("IpAddresses[0].Ip");
        call("ListResolverEndpointIpAddresses", "{\"ResolverEndpointId\":\"" + id
                + "\",\"MaxResults\":1,\"NextToken\":\"" + nextToken + "\"}")
                .then().statusCode(200).body("IpAddresses.size()", equalTo(1))
                .body("IpAddresses[0].Ip", not(equalTo(firstIp)))
                .body("containsKey('NextToken')", equalTo(false));
        call("ListResolverEndpoints", "{\"Filters\":[{\"Name\":\"CreatorRequestId\","
                + "\"Values\":[\"resolver-wire\"]}]}").then().statusCode(200)
                .body("ResolverEndpoints.Id", equalTo(List.of(id)));
        call("ListResolverEndpoints", "{\"Filters\":[{\"Name\":\"CreatorRequestId\","
                + "\"Values\":[\"resolver-wire-absent\"]}]}").then().statusCode(200)
                .body("ResolverEndpoints", equalTo(List.of()));
        callAs(AUTH_HEADER_WEST, "GetResolverEndpoint", "{\"ResolverEndpointId\":\"" + id + "\"}")
                .then().statusCode(400).body("__type", equalTo("ResourceNotFoundException"));
        callAs(AUTH_HEADER_WEST, "ListTagsForResource", resource).then().statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
        call("DeleteResolverEndpoint", "{\"ResolverEndpointId\":\"" + id + "\"}").then().statusCode(200);
        call("ListTagsForResource", resource).then().statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void endpointAllocationFailureRollsBackInterfacesAndDoesNotCreateAResource() {
        call("CreateResolverEndpoint", """
                {"Name":"duplicate-ip","CreatorRequestId":"duplicate-ip","Direction":"INBOUND",
                 "IpAddresses":[{"SubnetId":"subnet-aaa","Ip":"10.0.0.20"},
                                {"SubnetId":"subnet-aaa","Ip":"10.0.0.20"}]}
                """).then().statusCode(400).body("__type", equalTo("InvalidParameterException"));
        call("ListResolverEndpoints", """
                {"Filters":[{"Name":"CreatorRequestId","Values":["duplicate-ip"]}]}
                """).then().statusCode(200).body("ResolverEndpoints", equalTo(List.of()));
        String vpcId = networks.get("us-east-1").getFirst().getVpcId();
        assertEquals(List.of(), ec2.describeNetworkInterfaces("us-east-1", List.of(),
                Map.of("vpc-id", List.of(vpcId)), 0, null).networkInterfaces());
    }

    @Test
    void associationFiltersIntersectAndDeleteLeavesOtherRulesAssociationsIntact() {
        String firstRule = createRule("filtered-rule-a", "filtered-a.example.");
        String secondRule = createRule("filtered-rule-b", "filtered-b.example.");
        String firstAssociation = call("AssociateResolverRule", "{\"ResolverRuleId\":\"" + firstRule
                + "\",\"VPCId\":\"vpc-filter-a\"}").then().statusCode(200)
                .extract().path("ResolverRuleAssociation.Id");
        call("AssociateResolverRule", "{\"ResolverRuleId\":\"" + firstRule
                + "\",\"VPCId\":\"vpc-filter-b\"}").then().statusCode(200);
        String otherAssociation = call("AssociateResolverRule", "{\"ResolverRuleId\":\"" + secondRule
                + "\",\"VPCId\":\"vpc-filter-a\"}").then().statusCode(200)
                .extract().path("ResolverRuleAssociation.Id");
        String filter = "{\"Filters\":[{\"Name\":\"ResolverRuleId\",\"Values\":[\"" + firstRule
                + "\"]},{\"Name\":\"VPCId\",\"Values\":[\"vpc-filter-a\"]}]}";
        call("ListResolverRuleAssociations", filter).then().statusCode(200)
                .body("ResolverRuleAssociations.Id", equalTo(List.of(firstAssociation)));
        callAs(AUTH_HEADER_WEST, "ListResolverRuleAssociations", filter).then().statusCode(200)
                .body("ResolverRuleAssociations", equalTo(List.of()));
        callAs(AUTH_HEADER_WEST, "GetResolverRuleAssociation", "{\"ResolverRuleAssociationId\":\""
                + firstAssociation + "\"}").then().statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
        callAs(AUTH_HEADER_WEST, "DisassociateResolverRule", "{\"ResolverRuleId\":\"" + firstRule
                + "\",\"VPCId\":\"vpc-filter-a\"}").then().statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
        call("AssociateResolverRule", "{\"ResolverRuleId\":\"" + firstRule
                + "\",\"VPCId\":\"vpc-filter-a\"}").then().statusCode(400)
                .body("__type", equalTo("ResourceExistsException"));
        call("DeleteResolverRule", "{\"ResolverRuleId\":\"" + firstRule + "\"}").then().statusCode(400)
                .body("__type", equalTo("ResourceInUseException"));
        call("DisassociateResolverRule", "{\"ResolverRuleId\":\"" + firstRule
                + "\",\"VPCId\":\"vpc-filter-a\"}").then().statusCode(200);
        call("ListResolverRuleAssociations", filter).then().statusCode(200)
                .body("ResolverRuleAssociations", equalTo(List.of()));
        call("GetResolverRuleAssociation", "{\"ResolverRuleAssociationId\":\"" + otherAssociation + "\"}")
                .then().statusCode(200).body("ResolverRuleAssociation.ResolverRuleId", equalTo(secondRule));
        call("DisassociateResolverRule", "{\"ResolverRuleId\":\"" + firstRule
                + "\",\"VPCId\":\"vpc-filter-b\"}").then().statusCode(200);
        call("DeleteResolverRule", "{\"ResolverRuleId\":\"" + firstRule + "\"}").then().statusCode(200);
        call("DisassociateResolverRule", "{\"ResolverRuleId\":\"" + secondRule
                + "\",\"VPCId\":\"vpc-filter-a\"}").then().statusCode(200);
        call("DeleteResolverRule", "{\"ResolverRuleId\":\"" + secondRule + "\"}").then().statusCode(200);
    }

    @Test
    void ruleTagsFiltersPaginationAndRegionalAccountIsolationUseActualStoredResources() {
        String name = "regional-tagged-rule";
        String body = "{\"Name\":\"" + name + "\",\"CreatorRequestId\":\"" + name
                + "\",\"RuleType\":\"SYSTEM\",\"DomainName\":\"Corp.Example\","
                + "\"Tags\":[{\"Key\":\"owner\",\"Value\":\"a\"},{\"Key\":\"team\",\"Value\":\"dns\"}]}";
        Response created = call("CreateResolverRule", body);
        String id = created.then().statusCode(200).body("ResolverRule.DomainName", equalTo("corp.example."))
                .body("ResolverRule.containsKey('_Tags')", equalTo(false)).extract().path("ResolverRule.Id");
        String arn = created.path("ResolverRule.Arn");
        call("CreateResolverRule", body).then().statusCode(200).body("ResolverRule.Id", equalTo(id));
        Response tagsPage = call("ListTagsForResource", "{\"ResourceArn\":\"" + arn + "\",\"MaxResults\":1}");
        String token = tagsPage.then().statusCode(200).body("Tags.size()", equalTo(1))
                .extract().path("NextToken");
        call("ListTagsForResource", "{\"ResourceArn\":\"" + arn + "\",\"MaxResults\":1,\"NextToken\":\""
                + token + "\"}").then().statusCode(200).body("Tags[0].Key", equalTo("team"))
                .body("containsKey('NextToken')", equalTo(false));
        call("ListResolverRules", "{\"Filters\":[{\"Name\":\"CreatorRequestId\",\"Values\":[\"" + name
                + "\"]},{\"Name\":\"DomainName\",\"Values\":[\"CORP.EXAMPLE\"]}]}").then().statusCode(200)
                .body("ResolverRules.Id", equalTo(List.of(id)));
        call("ListResolverRules", "{\"Filters\":[{\"Name\":\"CreatorRequestId\",\"Values\":[]}]}")
                .then().statusCode(400).body("__type", equalTo("InvalidParameterException"));
        call("ListResolverRules", "{\"NextToken\":\"not-a-token\"}").then().statusCode(400)
                .body("__type", equalTo("InvalidNextTokenException"));
        String foreignAccount = AUTH_HEADER.replace("AKID", "111122223333");
        for (String auth : List.of(AUTH_HEADER_WEST, foreignAccount)) {
            callAs(auth, "ListResolverRules", "{\"Filters\":[{\"Name\":\"Id\",\"Values\":[\"" + id + "\"]}]}")
                    .then().statusCode(200).body("ResolverRules", equalTo(List.of()));
            callAs(auth, "GetResolverRule", "{\"ResolverRuleId\":\"" + id + "\"}").then().statusCode(400)
                    .body("__type", equalTo("ResourceNotFoundException"));
            callAs(auth, "DeleteResolverRule", "{\"ResolverRuleId\":\"" + id + "\"}").then().statusCode(400)
                    .body("__type", equalTo("ResourceNotFoundException"));
            callAs(auth, "TagResource", "{\"ResourceArn\":\"" + arn
                    + "\",\"Tags\":[{\"Key\":\"owner\",\"Value\":\"foreign\"}]}").then().statusCode(400)
                    .body("__type", equalTo("ResourceNotFoundException"));
        }
        call("ListTagsForResource", "{\"ResourceArn\":\"" + arn + "\"}").then().statusCode(200)
                .body("Tags.find { it.Key == 'owner' }.Value", equalTo("a"));
        call("UpdateResolverRule", "{\"ResolverRuleId\":\"" + id
                + "\",\"Config\":{\"Name\":\"should-not-persist\",\"TargetIps\":[]}}")
                .then().statusCode(400).body("__type", equalTo("InvalidParameterException"));
        call("GetResolverRule", "{\"ResolverRuleId\":\"" + id + "\"}").then().statusCode(200)
                .body("ResolverRule.Name", equalTo(name));
        call("DeleteResolverRule", "{\"ResolverRuleId\":\"" + id + "\"}").then().statusCode(200);
    }

    @Test
    void disassociateResolverRule_unknownAssociation_returnsResourceNotFound() {
        call("DisassociateResolverRule", "{\"ResolverRuleId\":\"rslvr-rr-neverassociated\","
                + "\"VPCId\":\"vpc-neverassociated\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }
}
