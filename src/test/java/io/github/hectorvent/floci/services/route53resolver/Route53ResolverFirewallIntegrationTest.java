package io.github.hectorvent.floci.services.route53resolver;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DNS Firewall rule-group coverage used by Alchemy
 * {@code ProfileResourceAssociation.test.ts}.
 */
@QuarkusTest
class Route53ResolverFirewallIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/route53resolver/aws4_request";

    @Inject
    Route53ResolverFirewallService service;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void reset() {
        service.clear();
    }

    @Test
    void createListDeleteFirewallRuleGroup_isIdempotentByCreatorRequestId() {
        String body = "{\"CreatorRequestId\":\"alchemy-test-r53p-frg\",\"Name\":\"alchemy-test-r53p-frg\"}";
        String id = invoke("CreateFirewallRuleGroup", body)
                .then()
                .statusCode(200)
                .body("FirewallRuleGroup.Id", startsWith("rslvr-frg-"))
                .body("FirewallRuleGroup.Name", equalTo("alchemy-test-r53p-frg"))
                .body("FirewallRuleGroup.Status", equalTo("COMPLETE"))
                .extract().path("FirewallRuleGroup.Id");
        String arn = invoke("CreateFirewallRuleGroup", body)
                .then()
                .statusCode(200)
                .body("FirewallRuleGroup.Id", equalTo(id))
                .extract().path("FirewallRuleGroup.Arn");
        assertTrue(arn.contains(":route53resolver:"));
        assertTrue(arn.contains(":firewall-rule-group/" + id));

        List<Map<String, Object>> groups = invoke("ListFirewallRuleGroups", "{}")
                .then()
                .statusCode(200)
                .extract().path("FirewallRuleGroups");
        assertTrue(groups.stream().anyMatch(item -> id.equals(item.get("Id"))));

        invoke("DeleteFirewallRuleGroup", "{\"FirewallRuleGroupId\":\"" + id + "\"}")
                .then()
                .statusCode(200)
                .body("FirewallRuleGroup.Id", equalTo(id));
        invoke("DeleteFirewallRuleGroup", "{\"FirewallRuleGroupId\":\"" + id + "\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    private static Response invoke(String action, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("Authorization", AUTH)
                .header("X-Amz-Target", Route53ResolverService.TARGET_PREFIX + action)
                .body(body)
                .when()
                .post("/");
    }
}
