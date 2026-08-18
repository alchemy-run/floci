package io.github.hectorvent.floci.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesReceiptRuleV1IntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-west-2/email/aws4_request";
    private static final String RS = "floci-v1-rules";

    private static io.restassured.specification.RequestSpecification req(String action) {
        return given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", AUTH)
                .formParam("Action", action);
    }

    @Test
    @Order(1)
    void createRuleSetAndRules_inOrder() {
        req("CreateReceiptRuleSet").formParam("RuleSetName", RS)
                .when().post("/").then().statusCode(200);

        req("CreateReceiptRule")
                .formParam("RuleSetName", RS)
                .formParam("Rule.Name", "first")
                .formParam("Rule.Enabled", "true")
                .formParam("Rule.ScanEnabled", "true")
                .formParam("Rule.Actions.member.1.StopAction.Scope", "RuleSet")
                .when().post("/").then().statusCode(200);

        req("CreateReceiptRule")
                .formParam("RuleSetName", RS)
                .formParam("After", "first")
                .formParam("Rule.Name", "second")
                .formParam("Rule.Actions.member.1.AddHeaderAction.HeaderName", "X-Test")
                .formParam("Rule.Actions.member.1.AddHeaderAction.HeaderValue", "inbound")
                .when().post("/").then().statusCode(200);
    }

    @Test
    @Order(2)
    void describeReceiptRule_returnsRule() {
        req("DescribeReceiptRule")
                .formParam("RuleSetName", RS)
                .formParam("RuleName", "second")
                .when().post("/").then().statusCode(200)
                .body(containsString("<Name>second</Name>"))
                .body(containsString("<HeaderValue>inbound</HeaderValue>"));
    }

    @Test
    @Order(3)
    void describeReceiptRuleSet_listsRulesInOrder() {
        req("DescribeReceiptRuleSet").formParam("RuleSetName", RS)
                .when().post("/").then().statusCode(200)
                .body(containsString("<Name>first</Name>"))
                .body(containsString("<Name>second</Name>"));
    }

    @Test
    @Order(4)
    void updateReceiptRule_replacesActions() {
        req("UpdateReceiptRule")
                .formParam("RuleSetName", RS)
                .formParam("Rule.Name", "second")
                .formParam("Rule.Enabled", "false")
                .formParam("Rule.TlsPolicy", "Require")
                .formParam("Rule.Actions.member.1.AddHeaderAction.HeaderName", "X-Test")
                .formParam("Rule.Actions.member.1.AddHeaderAction.HeaderValue", "updated")
                .when().post("/").then().statusCode(200);

        req("DescribeReceiptRule")
                .formParam("RuleSetName", RS)
                .formParam("RuleName", "second")
                .when().post("/").then().statusCode(200)
                .body(containsString("<Enabled>false</Enabled>"))
                .body(containsString("<TlsPolicy>Require</TlsPolicy>"))
                .body(containsString("<HeaderValue>updated</HeaderValue>"));
    }

    @Test
    @Order(5)
    void bounceAction_unverifiedSender_returnsIdentityNotVerified() {
        req("CreateReceiptRule")
                .formParam("RuleSetName", RS)
                .formParam("Rule.Name", "bounce")
                .formParam("Rule.Actions.member.1.BounceAction.SmtpReplyCode", "550")
                .formParam("Rule.Actions.member.1.BounceAction.Message", "gone")
                .formParam("Rule.Actions.member.1.BounceAction.Sender", "nobody@example.com")
                .when().post("/").then().statusCode(400)
                .body(containsString("Identity is not verified"));
    }

    @Test
    @Order(6)
    void deleteReceiptRule_thenDescribeMissing() {
        req("DeleteReceiptRule")
                .formParam("RuleSetName", RS)
                .formParam("RuleName", "second")
                .when().post("/").then().statusCode(200);

        req("DescribeReceiptRule")
                .formParam("RuleSetName", RS)
                .formParam("RuleName", "second")
                .when().post("/").then().statusCode(400)
                .body(containsString("<Code>RuleDoesNotExist</Code>"));
    }

    @Test
    @Order(7)
    void createAndListReceiptFilters() {
        req("CreateReceiptFilter")
                .formParam("Filter.Name", "floci-block")
                .formParam("Filter.IpFilter.Policy", "Block")
                .formParam("Filter.IpFilter.Cidr", "10.0.0.0/24")
                .when().post("/").then().statusCode(200);

        req("ListReceiptFilters")
                .when().post("/").then().statusCode(200)
                .body(containsString("<Name>floci-block</Name>"))
                .body(containsString("<Policy>Block</Policy>"))
                .body(containsString("<Cidr>10.0.0.0/24</Cidr>"));

        req("DeleteReceiptFilter").formParam("FilterName", "floci-block")
                .when().post("/").then().statusCode(200);

        req("ListReceiptFilters")
                .when().post("/").then().statusCode(200)
                .body(not(containsString("floci-block")));
    }

    @Test
    @Order(8)
    void cleanupRuleSet() {
        req("DeleteReceiptRuleSet").formParam("RuleSetName", RS)
                .when().post("/").then().statusCode(200);
    }
}
