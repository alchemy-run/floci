package io.github.hectorvent.floci.services.route53resolver;

import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JSON 1.1 Route 53 Resolver endpoint coverage used by Alchemy
 * ResolverEndpoint.test.ts: inbound create, OPERATIONAL immediately, tags,
 * CreatorRequestId filter, in-place tag update, and delete.
 */
@QuarkusTest
class ResolverEndpointIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/route53resolver/aws4_request";
    private static final String REGION = "us-east-1";

    @Inject
    Route53ResolverService service;

    @Inject
    Ec2Service ec2Service;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void reset() {
        service.clear();
    }

    @Test
    void getResolverEndpoint_missingId_returnsResourceNotFoundException() {
        r53r("GetResolverEndpoint", "{\"ResolverEndpointId\":\"rslvr-in-doesnotexist\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void inboundEndpoint_createGetTagsUpdateAndDelete() {
        Net net = defaultNetwork();
        String creator = "alchemy-r53r-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        String id = r53r("CreateResolverEndpoint", "{"
                + "\"CreatorRequestId\":\"" + creator + "\","
                + "\"Name\":\"" + creator + "\","
                + "\"Direction\":\"INBOUND\","
                + "\"SecurityGroupIds\":[\"" + net.securityGroupId + "\"],"
                + "\"IpAddresses\":["
                + "{\"SubnetId\":\"" + net.subnetA + "\"},"
                + "{\"SubnetId\":\"" + net.subnetB + "\"}"
                + "],"
                + "\"Tags\":[{\"Key\":\"fixture\",\"Value\":\"r53r-endpoint\"},{\"Key\":\"alchemy::id\",\"Value\":\"Inbound\"}]"
                + "}")
                .then()
                .statusCode(200)
                .body("ResolverEndpoint.Id", startsWith("rslvr-in-"))
                .body("ResolverEndpoint.Direction", equalTo("INBOUND"))
                .body("ResolverEndpoint.Status", equalTo("OPERATIONAL"))
                .body("ResolverEndpoint.HostVPCId", equalTo(net.vpcId))
                .body("ResolverEndpoint.IpAddressCount", equalTo(2))
                .extract().path("ResolverEndpoint.Id");

        String arn = r53r("GetResolverEndpoint", "{\"ResolverEndpointId\":\"" + id + "\"}")
                .then()
                .statusCode(200)
                .body("ResolverEndpoint.Status", equalTo("OPERATIONAL"))
                .body("ResolverEndpoint.Direction", equalTo("INBOUND"))
                .body("ResolverEndpoint.IpAddressCount", equalTo(2))
                .extract().path("ResolverEndpoint.Arn");
        assertTrue(arn.contains(":resolver-endpoint/"));

        r53r("ListResolverEndpoints", "{"
                + "\"Filters\":[{\"Name\":\"CreatorRequestId\",\"Values\":[\"" + creator + "\"]}]"
                + "}")
                .then()
                .statusCode(200)
                .body("ResolverEndpoints", hasSize(1))
                .body("ResolverEndpoints[0].Id", equalTo(id));

        r53r("ListTagsForResource", "{\"ResourceArn\":\"" + arn + "\"}")
                .then()
                .statusCode(200)
                .body("Tags.Key", hasItem("fixture"))
                .body("Tags.Key", hasItem("alchemy::id"));

        r53r("TagResource", "{\"ResourceArn\":\"" + arn
                + "\",\"Tags\":[{\"Key\":\"team\",\"Value\":\"dns\"}]}")
                .then()
                .statusCode(200);
        r53r("UntagResource", "{\"ResourceArn\":\"" + arn
                + "\",\"TagKeys\":[\"fixture\"]}")
                .then()
                .statusCode(200);

        r53r("ListTagsForResource", "{\"ResourceArn\":\"" + arn + "\"}")
                .then()
                .statusCode(200)
                .body("Tags.Key", hasItem("team"))
                .body("Tags.Key", hasItem("alchemy::id"))
                .body("Tags.Key", not(hasItem("fixture")));

        r53r("DeleteResolverEndpoint", "{\"ResolverEndpointId\":\"" + id + "\"}")
                .then()
                .statusCode(200)
                .body("ResolverEndpoint.Status", equalTo("DELETING"));

        r53r("GetResolverEndpoint", "{\"ResolverEndpointId\":\"" + id + "\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    private Net defaultNetwork() {
        List<Subnet> subnets = ec2Service.describeSubnets(REGION, List.of(),
                Map.of("default-for-az", List.of("true")));
        subnets = subnets.stream()
                .sorted(Comparator.comparing(Subnet::getSubnetId))
                .toList();
        assertTrue(subnets.size() >= 2, "default VPC has fewer than 2 default-for-AZ subnets");
        String vpcId = subnets.get(0).getVpcId();
        List<SecurityGroup> groups = ec2Service.describeSecurityGroups(REGION, List.of(), List.of(),
                Map.of("vpc-id", List.of(vpcId), "group-name", List.of("default")));
        assertTrue(!groups.isEmpty(), "default VPC has no default security group");
        return new Net(vpcId, subnets.get(0).getSubnetId(), subnets.get(1).getSubnetId(),
                groups.get(0).getGroupId());
    }

    private static Response r53r(String action, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", Route53ResolverService.TARGET_PREFIX + action)
                .header("Authorization", AUTH_HEADER)
                .body(body)
                .when()
                .post("/");
    }

    private record Net(String vpcId, String subnetA, String subnetB, String securityGroupId) {
    }
}
