package io.floci.aws.services.route53profiles;

import io.floci.aws.core.common.dns.DnsForwardingRule;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Route 53 Profiles: profiles, VPC associations (one profile per VPC), resource associations checked in the
 * owning service, rule group priorities, rule groups held in use, and Resolver rules reaching a VPC's DNS
 * through its profile.
 */
@QuarkusTest
class Route53ProfilesIntegrationTest {

    @Inject
    Route53ProfilesDnsForwardingRules dnsRules;

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    static String auth(String service) {
        return "AWS4-HMAC-SHA256 Credential=000000000000/20260101/us-east-1/" + service + "/aws4_request";
    }

    static RequestSpecification profiles() {
        return given().header("Authorization", auth("route53profiles")).contentType("application/json");
    }

    static RequestSpecification resolver(String action, String body) {
        return given().header("Authorization", auth("route53resolver"))
                .header("X-Amz-Target", "Route53Resolver." + action).contentType("application/x-amz-json-1.1")
                .body(body);
    }

    static String vpc(String cidr) {
        String xml = given().header("Authorization", auth("ec2")).contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateVpc").formParam("Version", "2016-11-15").formParam("CidrBlock", cidr)
                .post("/").then().statusCode(200).extract().asString();
        return xml.replaceAll("(?s).*<vpcId>([^<]+)</vpcId>.*", "$1");
    }

    static String encoded(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    static void awaitStatus(String path, String field, String status) {
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100))
                .until(() -> status.equals(profiles().get(path).then().extract().path(field + ".Status")));
    }

    @Test
    void profilesCarryDnsConfigurationToTheirVpcs() {
        String vpc = vpc("10.71.0.0/16");
        String other = vpc("10.72.0.0/16");
        String rule = resolver("CreateResolverRule", "{\"CreatorRequestId\":\"r53p-rule\",\"RuleType\":"
                + "\"FORWARD\",\"DomainName\":\"corp.example\",\"TargetIps\":[{\"Ip\":\"10.71.0.10\","
                + "\"Port\":53}]}").post("/").then().statusCode(200).extract().path("ResolverRule.Arn");
        String group = resolver("CreateFirewallRuleGroup", "{\"CreatorRequestId\":\"r53p-frg\",\"Name\":\"block\"}")
                .post("/").then().statusCode(200).body("FirewallRuleGroup.Id", startsWith("rslvr-frg-"))
                .extract().path("FirewallRuleGroup.Arn");
        String groupId = group.substring(group.lastIndexOf('/') + 1);
        String list = resolver("CreateFirewallDomainList", "{\"CreatorRequestId\":\"r53p-fdl\",\"Name\":\"bad\"}")
                .post("/").then().statusCode(200).extract().path("FirewallDomainList.Id");
        resolver("CreateFirewallRule", "{\"CreatorRequestId\":\"r53p-fr\",\"FirewallRuleGroupId\":\"" + groupId
                + "\",\"FirewallDomainListId\":\"" + list + "\",\"Priority\":1,\"Action\":\"BLOCK\",\"Name\":\"b\"}")
                .post("/").then().statusCode(400);
        resolver("CreateFirewallRule", "{\"CreatorRequestId\":\"r53p-fr\",\"FirewallRuleGroupId\":\"" + groupId
                + "\",\"FirewallDomainListId\":\"" + list + "\",\"Priority\":1,\"Action\":\"BLOCK\","
                + "\"BlockResponse\":\"NXDOMAIN\",\"Name\":\"b\"}").post("/").then().statusCode(200);
        resolver("GetFirewallRuleGroup", "{\"FirewallRuleGroupId\":\"" + groupId + "\"}").post("/").then()
                .body("FirewallRuleGroup.RuleCount", equalTo(1));

        // A profile is created asynchronously.
        String profile = profiles().body("{\"Name\":\"shared-dns\",\"ClientToken\":\"r53p-1\",\"Tags\":[{\"Key\":"
                + "\"team\",\"Value\":\"net\"}]}").post("/profile").then().statusCode(200)
                .body("Profile.Status", equalTo("CREATING")).body("Profile.Id", startsWith("rp-"))
                .extract().path("Profile.Id");
        profiles().body("{\"Name\":\"x\",\"ProfileId\":\"" + profile + "\",\"ResourceId\":\"" + vpc + "\"}")
                .post("/profileassociation").then().statusCode(409);
        awaitStatus("/profile/" + profile, "Profile", "COMPLETE");
        String arn = profiles().get("/profile/" + profile).then().extract().path("Profile.Arn");
        profiles().get("/tags/" + encoded(arn)).then().statusCode(200).body("Tags.team", equalTo("net"));
        profiles().get("/profiles").then().body("ProfileSummaries.Id", hasItem(profile));

        // VPCs: must exist, and take one profile.
        profiles().body("{\"Name\":\"x\",\"ProfileId\":\"" + profile + "\",\"ResourceId\":\"vpc-0000000000\"}")
                .post("/profileassociation").then().statusCode(404);
        String association = profiles().body("{\"Name\":\"main\",\"ProfileId\":\"" + profile + "\",\"ResourceId\":\""
                + vpc + "\"}").post("/profileassociation").then().statusCode(200)
                .body("ProfileAssociation.Id", startsWith("rpassoc-")).extract().path("ProfileAssociation.Id");
        profiles().body("{\"Name\":\"again\",\"ProfileId\":\"" + profile + "\",\"ResourceId\":\"" + vpc + "\"}")
                .post("/profileassociation").then().statusCode(400);
        awaitStatus("/profileassociation/" + association, "ProfileAssociation", "COMPLETE");

        // Resources are checked where they live.
        profiles().body("{\"Name\":\"x\",\"ProfileId\":\"" + profile + "\",\"ResourceArn\":\"" + rule
                .replace("rslvr-rr-", "rslvr-rr-0") + "\"}").post("/profileresourceassociation").then()
                .statusCode(404);
        profiles().body("{\"Name\":\"x\",\"ProfileId\":\"" + profile + "\",\"ResourceArn\":\"" + group + "\"}")
                .post("/profileresourceassociation").then().statusCode(400);
        String ruleAssociation = profiles().body("{\"Name\":\"corp\",\"ProfileId\":\"" + profile
                + "\",\"ResourceArn\":\"" + rule + "\"}").post("/profileresourceassociation").then().statusCode(200)
                .body("ProfileResourceAssociation.ResourceType", equalTo("RESOLVER_RULE"))
                .extract().path("ProfileResourceAssociation.Id");
        String groupAssociation = profiles().body("{\"Name\":\"fw\",\"ProfileId\":\"" + profile
                + "\",\"ResourceArn\":\"" + group + "\",\"ResourceProperties\":\"{\\\"priority\\\": 102}\"}")
                .post("/profileresourceassociation").then().statusCode(200)
                .body("ProfileResourceAssociation.ResourceType", equalTo("FIREWALL_RULE_GROUP"))
                .body("ProfileResourceAssociation.ResourceProperties", equalTo("{\"priority\":102}"))
                .body("ProfileResourceAssociation.Id", startsWith("rpr-"))
                .extract().path("ProfileResourceAssociation.Id");

        // The profile's rule reaches the associated VPC's DNS once both associations are complete.
        awaitStatus("/profileresourceassociation/" + ruleAssociation, "ProfileResourceAssociation", "COMPLETE");
        await().atMost(Duration.ofSeconds(10)).until(() -> !dnsRules.rulesFor("000000000000", "us-east-1", vpc)
                .isEmpty());
        DnsForwardingRule forwarded = dnsRules.rulesFor("000000000000", "us-east-1", vpc).get(0);
        assertTrue(forwarded.matches("git.corp.example"));
        assertEquals("10.71.0.10", forwarded.targets().get(0).address());
        assertEquals(List.of(), dnsRules.rulesFor("000000000000", "us-east-1", other));

        // Rule group priorities update in place and stay unique; an attached rule group can't be deleted.
        awaitStatus("/profileresourceassociation/" + groupAssociation, "ProfileResourceAssociation", "COMPLETE");
        profiles().body("{\"ResourceProperties\":\"{\\\"priority\\\":50}\"}")
                .patch("/profileresourceassociation/" + groupAssociation).then().statusCode(400);
        profiles().body("{\"ResourceProperties\":\"{\\\"priority\\\":103}\"}")
                .patch("/profileresourceassociation/" + groupAssociation).then().statusCode(200)
                .body("ProfileResourceAssociation.Status", equalTo("UPDATING"));
        awaitStatus("/profileresourceassociation/" + groupAssociation, "ProfileResourceAssociation", "COMPLETE");
        profiles().get("/profileresourceassociation/" + groupAssociation).then()
                .body("ProfileResourceAssociation.ResourceProperties", equalTo("{\"priority\":103}"));
        profiles().get("/profileresourceassociations/profileid/" + profile + "?resourceType=FIREWALL_RULE_GROUP")
                .then().statusCode(200).body("ProfileResourceAssociations.Id", equalTo(List.of(groupAssociation)));
        resolver("DeleteFirewallRuleGroup", "{\"FirewallRuleGroupId\":\"" + groupId + "\"}").post("/").then()
                .statusCode(400).body("__type", containsString("ConflictException"));

        // A profile in use by a VPC can't be deleted; removing the association takes its rules off the VPC.
        profiles().delete("/profile/" + profile).then().statusCode(409);
        profiles().delete("/profileassociation/Profileid/" + profile + "/resourceid/" + vpc).then().statusCode(200)
                .body("ProfileAssociation.Status", equalTo("DELETING"));
        assertEquals(List.of(), dnsRules.rulesFor("000000000000", "us-east-1", vpc));
        profiles().delete("/profileresourceassociation/profileid/" + profile + "/resourcearn/" + encoded(group))
                .then().statusCode(200);
        resolver("DeleteFirewallRuleGroup", "{\"FirewallRuleGroupId\":\"" + groupId + "\"}").post("/").then()
                .statusCode(200).body("FirewallRuleGroup.Status", equalTo("DELETING"));
        profiles().delete("/profile/" + profile).then().statusCode(200).body("Profile.Status", equalTo("DELETING"));
        await().atMost(Duration.ofSeconds(10)).until(() -> profiles().get("/profile/" + profile).then().extract()
                .statusCode() == 404);
        profiles().get("/profile/" + profile).then().body("__type", containsString("ResourceNotFoundException"));
    }
}
