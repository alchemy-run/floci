package io.github.hectorvent.floci.services.securityhub;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class SecurityHubLifecycleIntegrationTest {
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=555555555555/20260921/us-east-1/securityhub/aws4_request";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void unchangedBindingRoutesUseSecurityHubStateAndIsolateAccountsAndRegions() {
        String owner = "AWS4-HMAC-SHA256 Credential=555555555551/20260921/us-east-1/securityhub/aws4_request";
        String other = "AWS4-HMAC-SHA256 Credential=555555555552/20260921/us-east-1/securityhub/aws4_request";
        String otherRegion = "AWS4-HMAC-SHA256 Credential=555555555551/20260921/eu-west-1/securityhub/aws4_request";
        for (String auth : new String[] {owner, other, otherRegion}) {
            given().header("Authorization", auth).contentType("application/json")
                    .body("{\"EnableDefaultStandards\":false}").post("/accounts").then().statusCode(200);
        }
        try {
            given().header("Authorization", owner).get("/products").then().statusCode(200)
                    .body("Products[0].ProductArn", equalTo("arn:aws:securityhub:us-east-1:555555555551:product/555555555551/default"));
            given().header("Authorization", owner).contentType("application/json").body("{}")
                    .post("/actionTargets/get").then().statusCode(200).body("ActionTargets.size()", equalTo(0));
            given().header("Authorization", owner).contentType("application/json").body("{}")
                    .post("/standards/get").then().statusCode(200).body("StandardsSubscriptions.size()", equalTo(0));
            given().header("Authorization", owner).get("/securityControls/definitions").then().statusCode(200)
                    .body("SecurityControlDefinitions[0].SecurityControlId", equalTo("IAM.1"));
            given().header("Authorization", owner).queryParam("SecurityControlId", "IAM.1")
                    .get("/securityControl/definition").then().statusCode(200)
                    .body("SecurityControlDefinition.SeverityRating", equalTo("HIGH"));
            given().header("Authorization", owner).get("/findingAggregator/list").then().statusCode(200)
                    .body("FindingAggregators.size()", equalTo(0));
            given().header("Authorization", owner).get("/members").then().statusCode(200)
                    .body("Members.size()", equalTo(0));
            given().header("Authorization", owner).get("/invitations").then().statusCode(200)
                    .body("Invitations.size()", equalTo(0));
            given().header("Authorization", owner).get("/invitations/count").then().statusCode(200)
                    .body("InvitationsCount", equalTo(0));
            given().header("Authorization", owner).get("/administrator").then().statusCode(200);
            given().header("Authorization", owner).contentType("application/json").body("""
                    {"Findings":[{"SchemaVersion":"2018-10-08","Id":"custom/roundtrip","GeneratorId":"alchemy",
                    "ProductArn":"arn:aws:securityhub:us-east-1:555555555551:product/555555555551/default",
                    "AwsAccountId":"555555555551","Types":["Software and Configuration Checks"],
                    "CreatedAt":"2026-09-21T00:00:00Z","UpdatedAt":"2026-09-21T00:00:00Z",
                    "Severity":{"Label":"INFORMATIONAL"},"Title":"Roundtrip","Description":"Imported by caller",
                    "Resources":[{"Type":"Other","Id":"resource"}]}]}
                    """).post("/findings/import").then().statusCode(200)
                    .body("SuccessCount", equalTo(1)).body("FailedCount", equalTo(0));
            given().header("Authorization", owner).contentType("application/json").body("""
                    {"Filters":{"Id":[{"Value":"custom/roundtrip","Comparison":"EQUALS"}]}}
                    """).post("/findings").then().statusCode(200)
                    .body("Findings.size()", equalTo(1)).body("Findings[0].Title", equalTo("Roundtrip"));
            for (String auth : new String[] {other, otherRegion}) {
                given().header("Authorization", auth).contentType("application/json").body("{}")
                        .post("/findings").then().statusCode(200).body("Findings.size()", equalTo(0));
            }
            given().header("Authorization", owner).contentType("application/json").body("""
                    {"FindingIdentifiers":[{"Id":"custom/roundtrip",
                    "ProductArn":"arn:aws:securityhub:us-east-1:555555555551:product/555555555551/default"}],
                    "Workflow":{"Status":"NOTIFIED"},"Note":{"Text":"acknowledged","UpdatedBy":"alchemy"}}
                    """).patch("/findings/batchupdate").then().statusCode(200)
                    .body("ProcessedFindings.size()", equalTo(1)).body("UnprocessedFindings.size()", equalTo(0));
            given().header("Authorization", owner).contentType("application/json").body("""
                    {"FindingIdentifier":{"Id":"custom/roundtrip",
                    "ProductArn":"arn:aws:securityhub:us-east-1:555555555551:product/555555555551/default"}}
                    """).post("/findingHistory/get").then().statusCode(200).body("Records.size()", equalTo(2));
        } finally {
            for (String auth : new String[] {owner, other, otherRegion}) {
                given().header("Authorization", auth).delete("/accounts").then().statusCode(200);
            }
        }
    }

    @Test
    void disableRemovesHubAccessAndReenableRestoresDefaultSettings() {
        given().header("Authorization", AUTH).contentType("application/json").body("{}")
                .post("/accounts").then().statusCode(200);
        given().header("Authorization", AUTH).contentType("application/json")
                .body("{\"AutoEnableControls\":false,\"ControlFindingGenerator\":\"STANDARD_CONTROL\"}")
                .patch("/accounts").then().statusCode(200);
        given().header("Authorization", AUTH).get("/accounts").then().statusCode(200)
                .body("AutoEnableControls", equalTo(false));
        given().header("Authorization",
                        "AWS4-HMAC-SHA256 Credential=666666666666/20260921/us-east-1/securityhub/aws4_request")
                .delete("/accounts").then().statusCode(404);
        given().header("Authorization", AUTH).get("/accounts").then().statusCode(200);
        given().header("Authorization", AUTH).delete("/accounts").then().statusCode(200);
        given().header("Authorization", AUTH).get("/accounts").then().statusCode(404);
        given().header("Authorization", AUTH).delete("/accounts").then().statusCode(404);
        given().header("Authorization", AUTH).contentType("application/json").body("{}")
                .post("/accounts").then().statusCode(200);
        try {
            given().header("Authorization", AUTH).get("/accounts").then().statusCode(200)
                    .body("AutoEnableControls", equalTo(true))
                    .body("ControlFindingGenerator", equalTo("SECURITY_CONTROL"));
        } finally {
            given().header("Authorization", AUTH).delete("/accounts").then().statusCode(200);
        }
    }
}
