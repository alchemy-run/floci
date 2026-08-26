package io.github.hectorvent.floci.services.route53resolver;

import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Vpc;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JSON 1.1 Route 53 Resolver coverage used by Alchemy ResolverRule.test.ts:
 * outbound endpoint + FORWARD rule create/get/update/replace/tags/delete.
 */
@QuarkusTest
class Route53ResolverIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/route53resolver/aws4_request";

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
    void getResolverRule_missingId_returnsResourceNotFoundException() {
        r53r("GetResolverRule", "{\"ResolverRuleId\":\"rslvr-rr-does-not-exist\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void forwardRule_createUpdateTagsReplaceAndDelete() {
        String creator = "ep-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String endpointId = r53r("CreateResolverEndpoint", "{"
                + "\"CreatorRequestId\":\"" + creator + "\","
                + "\"Name\":\"" + creator + "\","
                + "\"Direction\":\"OUTBOUND\","
                + "\"SecurityGroupIds\":[\"sg-12345678\"],"
                + "\"IpAddresses\":[{\"SubnetId\":\"subnet-aaa\"},{\"SubnetId\":\"subnet-bbb\"}]"
                + "}")
                .then()
                .statusCode(200)
                .body("ResolverEndpoint.Id", startsWith("rslvr-out-"))
                .body("ResolverEndpoint.Status", equalTo("OPERATIONAL"))
                .extract().path("ResolverEndpoint.Id");

        String ruleCreator = "rule-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String ruleId = r53r("CreateResolverRule", "{"
                + "\"CreatorRequestId\":\"" + ruleCreator + "\","
                + "\"Name\":\"" + ruleCreator + "\","
                + "\"RuleType\":\"FORWARD\","
                + "\"DomainName\":\"corp.alchemy-r53r-test.internal\","
                + "\"ResolverEndpointId\":\"" + endpointId + "\","
                + "\"TargetIps\":[{\"Ip\":\"192.168.10.10\"}],"
                + "\"Tags\":[{\"Key\":\"fixture\",\"Value\":\"r53r-rule\"},{\"Key\":\"alchemy::id\",\"Value\":\"Forward\"}]"
                + "}")
                .then()
                .statusCode(200)
                .body("ResolverRule.Id", startsWith("rslvr-rr-"))
                .body("ResolverRule.Arn", notNullValue())
                .body("ResolverRule.RuleType", equalTo("FORWARD"))
                .body("ResolverRule.DomainName", equalTo("corp.alchemy-r53r-test.internal."))
                .body("ResolverRule.ResolverEndpointId", equalTo(endpointId))
                .body("ResolverRule.TargetIps[0].Ip", equalTo("192.168.10.10"))
                .extract().path("ResolverRule.Id");

        String ruleArn = r53r("GetResolverRule", "{\"ResolverRuleId\":\"" + ruleId + "\"}")
                .then()
                .statusCode(200)
                .body("ResolverRule.Status", equalTo("COMPLETE"))
                .extract().path("ResolverRule.Arn");

        r53r("ListTagsForResource", "{\"ResourceArn\":\"" + ruleArn + "\"}")
                .then()
                .statusCode(200)
                .body("Tags.Key", hasItem("alchemy::id"))
                .body("Tags.Key", hasItem("fixture"));

        r53r("ListResolverRules",
                "{\"Filters\":[{\"Name\":\"CreatorRequestId\",\"Values\":[\"" + ruleCreator + "\"]}]}")
                .then()
                .statusCode(200)
                .body("ResolverRules[0].Id", equalTo(ruleId));

        r53r("UpdateResolverRule", "{"
                + "\"ResolverRuleId\":\"" + ruleId + "\","
                + "\"Config\":{\"TargetIps\":[{\"Ip\":\"192.168.10.11\"}]}"
                + "}")
                .then()
                .statusCode(200)
                .body("ResolverRule.Id", equalTo(ruleId))
                .body("ResolverRule.TargetIps[0].Ip", equalTo("192.168.10.11"));

        String replacementCreator = "rule2-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String replacementId = r53r("CreateResolverRule", "{"
                + "\"CreatorRequestId\":\"" + replacementCreator + "\","
                + "\"Name\":\"" + replacementCreator + "\","
                + "\"RuleType\":\"FORWARD\","
                + "\"DomainName\":\"corp2.alchemy-r53r-test.internal\","
                + "\"ResolverEndpointId\":\"" + endpointId + "\","
                + "\"TargetIps\":[{\"Ip\":\"192.168.10.11\"}]"
                + "}")
                .then()
                .statusCode(200)
                .body("ResolverRule.Id", not(equalTo(ruleId)))
                .body("ResolverRule.DomainName", equalTo("corp2.alchemy-r53r-test.internal."))
                .extract().path("ResolverRule.Id");

        r53r("DeleteResolverRule", "{\"ResolverRuleId\":\"" + ruleId + "\"}")
                .then()
                .statusCode(200);

        r53r("GetResolverRule", "{\"ResolverRuleId\":\"" + ruleId + "\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));

        r53r("DeleteResolverRule", "{\"ResolverRuleId\":\"" + replacementId + "\"}")
                .then()
                .statusCode(200);

        r53r("DeleteResolverEndpoint", "{\"ResolverEndpointId\":\"" + endpointId + "\"}")
                .then()
                .statusCode(200);

        r53r("GetResolverEndpoint", "{\"ResolverEndpointId\":\"" + endpointId + "\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void deleteResolverRule_whileAssociated_returnsResourceInUseException() {
        String creator = "ep-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String endpointId = r53r("CreateResolverEndpoint", "{"
                + "\"CreatorRequestId\":\"" + creator + "\","
                + "\"Direction\":\"OUTBOUND\","
                + "\"SecurityGroupIds\":[\"sg-12345678\"],"
                + "\"IpAddresses\":[{\"SubnetId\":\"subnet-aaa\"},{\"SubnetId\":\"subnet-bbb\"}]"
                + "}")
                .then()
                .statusCode(200)
                .extract().path("ResolverEndpoint.Id");

        String ruleId = r53r("CreateResolverRule", "{"
                + "\"CreatorRequestId\":\"rule-" + creator + "\","
                + "\"RuleType\":\"FORWARD\","
                + "\"DomainName\":\"assoc.alchemy-r53r-test.internal\","
                + "\"ResolverEndpointId\":\"" + endpointId + "\","
                + "\"TargetIps\":[{\"Ip\":\"192.168.10.10\"}]"
                + "}")
                .then()
                .statusCode(200)
                .extract().path("ResolverRule.Id");

        String vpcId = defaultVpcId();
        r53r("AssociateResolverRule", "{"
                + "\"ResolverRuleId\":\"" + ruleId + "\","
                + "\"VPCId\":\"" + vpcId + "\""
                + "}")
                .then()
                .statusCode(200)
                .body("ResolverRuleAssociation.Status", equalTo("COMPLETE"));

        r53r("DeleteResolverRule", "{\"ResolverRuleId\":\"" + ruleId + "\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceInUseException"));

        r53r("ListResolverRuleAssociations",
                "{\"Filters\":[{\"Name\":\"ResolverRuleId\",\"Values\":[\"" + ruleId + "\"]}]}")
                .then()
                .statusCode(200)
                .body("ResolverRuleAssociations[0].VPCId", equalTo(vpcId));

        r53r("DisassociateResolverRule", "{"
                + "\"ResolverRuleId\":\"" + ruleId + "\","
                + "\"VPCId\":\"" + vpcId + "\""
                + "}")
                .then()
                .statusCode(200);

        r53r("DeleteResolverRule", "{\"ResolverRuleId\":\"" + ruleId + "\"}")
                .then()
                .statusCode(200);
    }

    @Test
    void outboundBindings_readIpsUpdateTargetAndListEmptyAssociations() {
        String creator = "bind-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String endpointId = r53r("CreateResolverEndpoint", "{"
                + "\"CreatorRequestId\":\"" + creator + "\","
                + "\"Name\":\"" + creator + "\","
                + "\"Direction\":\"OUTBOUND\","
                + "\"SecurityGroupIds\":[\"sg-12345678\"],"
                + "\"IpAddresses\":[{\"SubnetId\":\"subnet-aaa\"},{\"SubnetId\":\"subnet-bbb\"}]"
                + "}")
                .then()
                .statusCode(200)
                .body("ResolverEndpoint.Direction", equalTo("OUTBOUND"))
                .body("ResolverEndpoint.Status", equalTo("OPERATIONAL"))
                .body("ResolverEndpoint.IpAddressCount", equalTo(2))
                .extract().path("ResolverEndpoint.Id");

        Response ips = r53r("ListResolverEndpointIpAddresses",
                "{\"ResolverEndpointId\":\"" + endpointId + "\"}");
        ips.then()
                .statusCode(200)
                .body("IpAddresses", hasSize(2));
        java.util.List<String> addresses = ips.jsonPath().getList("IpAddresses.Ip");
        java.util.List<String> statuses = ips.jsonPath().getList("IpAddresses.Status");
        assertEquals(2, addresses.size());
        for (String ip : addresses) {
            assertTrue(ip.matches("^\\d+\\.\\d+\\.\\d+\\.\\d+$"), ip);
        }
        for (String status : statuses) {
            assertEquals("ATTACHED", status);
        }

        String ruleId = r53r("CreateResolverRule", "{"
                + "\"CreatorRequestId\":\"rule-" + creator + "\","
                + "\"RuleType\":\"FORWARD\","
                + "\"DomainName\":\"bindings.alchemy-r53r-test.internal\","
                + "\"ResolverEndpointId\":\"" + endpointId + "\","
                + "\"TargetIps\":[{\"Ip\":\"10.100.0.10\"}]"
                + "}")
                .then()
                .statusCode(200)
                .body("ResolverRule.DomainName", equalTo("bindings.alchemy-r53r-test.internal."))
                .body("ResolverRule.RuleType", equalTo("FORWARD"))
                .body("ResolverRule.TargetIps[0].Ip", equalTo("10.100.0.10"))
                .extract().path("ResolverRule.Id");

        r53r("UpdateResolverRule", "{"
                + "\"ResolverRuleId\":\"" + ruleId + "\","
                + "\"Config\":{\"TargetIps\":[{\"Ip\":\"10.100.0.11\",\"Port\":53}]}"
                + "}")
                .then()
                .statusCode(200)
                .body("ResolverRule.TargetIps[0].Ip", equalTo("10.100.0.11"));

        r53r("GetResolverRule", "{\"ResolverRuleId\":\"" + ruleId + "\"}")
                .then()
                .statusCode(200)
                .body("ResolverRule.TargetIps[0].Ip", equalTo("10.100.0.11"));

        r53r("ListResolverRuleAssociations",
                "{\"Filters\":[{\"Name\":\"ResolverRuleId\",\"Values\":[\"" + ruleId + "\"]}]}")
                .then()
                .statusCode(200)
                .body("ResolverRuleAssociations", hasSize(0));
    }

    @Test
    void associateResolverRule_missingRuleAgainstRealVpc_returnsResourceNotFoundException() {
        r53r("AssociateResolverRule", "{"
                + "\"ResolverRuleId\":\"rslvr-rr-00000000000000000\","
                + "\"VPCId\":\"" + defaultVpcId() + "\""
                + "}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void associateResolverRule_missingVpc_returnsInvalidParameterException() {
        r53r("AssociateResolverRule", "{"
                + "\"ResolverRuleId\":\"rslvr-rr-00000000000000000\","
                + "\"VPCId\":\"vpc-doesnotexist00000\""
                + "}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidParameterException"));
    }

    @Test
    void getResolverRuleAssociation_missingId_returnsResourceNotFoundException() {
        r53r("GetResolverRuleAssociation",
                "{\"ResolverRuleAssociationId\":\"rslvr-rrassoc-doesnotexist\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void associateGetListAndDisassociateResolverRule() {
        String vpcId = defaultVpcId();
        String creator = "assoc-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String endpointId = r53r("CreateResolverEndpoint", "{"
                + "\"CreatorRequestId\":\"" + creator + "\","
                + "\"Direction\":\"OUTBOUND\","
                + "\"SecurityGroupIds\":[\"sg-12345678\"],"
                + "\"IpAddresses\":[{\"SubnetId\":\"subnet-aaa\"},{\"SubnetId\":\"subnet-bbb\"}]"
                + "}")
                .then()
                .statusCode(200)
                .extract().path("ResolverEndpoint.Id");

        String ruleId = r53r("CreateResolverRule", "{"
                + "\"CreatorRequestId\":\"rule-" + creator + "\","
                + "\"RuleType\":\"FORWARD\","
                + "\"DomainName\":\"assoc.alchemy-r53r-test.internal\","
                + "\"ResolverEndpointId\":\"" + endpointId + "\","
                + "\"TargetIps\":[{\"Ip\":\"192.168.20.10\"}]"
                + "}")
                .then()
                .statusCode(200)
                .extract().path("ResolverRule.Id");

        String associationId = r53r("AssociateResolverRule", "{"
                + "\"ResolverRuleId\":\"" + ruleId + "\","
                + "\"VPCId\":\"" + vpcId + "\""
                + "}")
                .then()
                .statusCode(200)
                .body("ResolverRuleAssociation.Id", startsWith("rslvr-rrassoc-"))
                .body("ResolverRuleAssociation.ResolverRuleId", equalTo(ruleId))
                .body("ResolverRuleAssociation.VPCId", equalTo(vpcId))
                .body("ResolverRuleAssociation.Status", equalTo("COMPLETE"))
                .extract().path("ResolverRuleAssociation.Id");

        r53r("GetResolverRuleAssociation",
                "{\"ResolverRuleAssociationId\":\"" + associationId + "\"}")
                .then()
                .statusCode(200)
                .body("ResolverRuleAssociation.Id", equalTo(associationId))
                .body("ResolverRuleAssociation.Status", equalTo("COMPLETE"));

        r53r("ListResolverRuleAssociations", "{"
                + "\"Filters\":["
                + "{\"Name\":\"ResolverRuleId\",\"Values\":[\"" + ruleId + "\"]},"
                + "{\"Name\":\"VPCId\",\"Values\":[\"" + vpcId + "\"]}"
                + "]}")
                .then()
                .statusCode(200)
                .body("ResolverRuleAssociations", hasSize(1))
                .body("ResolverRuleAssociations[0].Id", equalTo(associationId));

        r53r("DisassociateResolverRule", "{"
                + "\"ResolverRuleId\":\"" + ruleId + "\","
                + "\"VPCId\":\"" + vpcId + "\""
                + "}")
                .then()
                .statusCode(200);

        r53r("GetResolverRuleAssociation",
                "{\"ResolverRuleAssociationId\":\"" + associationId + "\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    private String defaultVpcId() {
        List<Vpc> vpcs = ec2Service.describeVpcs("us-east-1", List.of(), Map.of());
        return vpcs.stream()
                .filter(Vpc::isDefault)
                .map(Vpc::getVpcId)
                .findFirst()
                .orElseGet(() -> {
                    assertTrue(!vpcs.isEmpty(), "emulator has no VPCs");
                    return vpcs.get(0).getVpcId();
                });
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
}
