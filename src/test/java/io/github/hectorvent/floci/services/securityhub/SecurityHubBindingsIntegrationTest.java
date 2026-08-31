package io.github.hectorvent.floci.services.securityhub;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Verifies Security Hub restJson1 operations Alchemy {@code Bindings.test.ts}
 * drives through the Lambda fixture.
 */
@QuarkusTest
class SecurityHubBindingsIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String ACCOUNT = "000000000831";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void describeHubWhenNotEnabledIsInvalidAccess() {
        given()
                .header("Authorization", auth("000000000832", EAST))
                .when()
                .get("/accounts")
                .then()
                .statusCode(401)
                .body("__type", equalTo("InvalidAccessException"));
    }

    @Test
    void findingsTriageAndCatalogReads() {
        String authorization = auth(ACCOUNT, EAST);
        String productArn = "arn:aws:securityhub:" + EAST + ":" + ACCOUNT + ":product/" + ACCOUNT + "/default";
        String findingId = "alchemy/securityhub-bindings/test-finding-1";

        given()
                .header("Authorization", authorization)
                .when()
                .get("/accounts")
                .then()
                .statusCode(401);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"EnableDefaultStandards\":false,\"ControlFindingGenerator\":\"SECURITY_CONTROL\"}")
                .when()
                .post("/accounts")
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/accounts")
                .then()
                .statusCode(200)
                .body("HubArn", equalTo("arn:aws:securityhub:" + EAST + ":" + ACCOUNT + ":hub/default"))
                .body("AutoEnableControls", equalTo(true))
                .body("ControlFindingGenerator", equalTo("SECURITY_CONTROL"))
                .body("SubscribedAt", notNullValue());

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {"Findings":[{
                          "SchemaVersion":"2018-10-08",
                          "Id":"%s",
                          "ProductArn":"%s",
                          "GeneratorId":"alchemy-bindings-test",
                          "AwsAccountId":"%s",
                          "Types":["Software and Configuration Checks"],
                          "CreatedAt":"2026-01-01T00:00:00.000Z",
                          "UpdatedAt":"2026-01-01T00:00:00.000Z",
                          "Severity":{"Label":"INFORMATIONAL"},
                          "Title":"Alchemy SecurityHub bindings test finding",
                          "Description":"Synthetic finding.",
                          "Resources":[{"Type":"Other","Id":"alchemy-bindings-test-resource","Partition":"aws","Region":"%s"}]
                        }]}
                        """.formatted(findingId, productArn, ACCOUNT, EAST))
                .when()
                .post("/findings/import")
                .then()
                .statusCode(200)
                .body("FailedCount", equalTo(0))
                .body("SuccessCount", equalTo(1))
                .body("FailedFindings", hasSize(0));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"Filters\":{\"Id\":[{\"Value\":\"" + findingId + "\",\"Comparison\":\"EQUALS\"}]}}")
                .when()
                .post("/findings")
                .then()
                .statusCode(200)
                .body("Findings", hasSize(1))
                .body("Findings[0].Title", equalTo("Alchemy SecurityHub bindings test finding"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {"FindingIdentifiers":[{"Id":"%s","ProductArn":"%s"}],
                         "Workflow":{"Status":"NOTIFIED"},
                         "Note":{"Text":"acknowledged","UpdatedBy":"alchemy"}}
                        """.formatted(findingId, productArn))
                .when()
                .patch("/findings/batchupdate")
                .then()
                .statusCode(200)
                .body("ProcessedFindings", hasSize(1))
                .body("UnprocessedFindings", hasSize(0));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"FindingIdentifier\":{\"Id\":\"" + findingId + "\",\"ProductArn\":\"" + productArn + "\"}}")
                .when()
                .post("/findingHistory/get")
                .then()
                .statusCode(200)
                .body("Records", hasSize(greaterThan(0)));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/standards")
                .then()
                .statusCode(200)
                .body("Standards", hasSize(greaterThan(0)));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/standards/get")
                .then()
                .statusCode(200)
                .body("StandardsSubscriptions", hasSize(0));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/securityControls/definitions?MaxResults=10")
                .then()
                .statusCode(200)
                .body("SecurityControlDefinitions", hasSize(greaterThan(0)));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/securityControl/definition?SecurityControlId=IAM.1")
                .then()
                .statusCode(200)
                .body("SecurityControlDefinition.SecurityControlId", equalTo("IAM.1"))
                .body("SecurityControlDefinition.SeverityRating", notNullValue());

        given()
                .header("Authorization", authorization)
                .when()
                .get("/products?MaxResults=10")
                .then()
                .statusCode(200)
                .body("Products", hasSize(greaterThan(0)));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/productSubscriptions")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/actionTargets/get")
                .then()
                .statusCode(200)
                .body("ActionTargets", hasSize(0));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/automationrules/list")
                .then()
                .statusCode(200)
                .body("AutomationRulesMetadata", hasSize(0));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/findingAggregator/list")
                .then()
                .statusCode(200)
                .body("FindingAggregators", hasSize(0));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/insights/get")
                .then()
                .statusCode(200)
                .body("Insights", hasSize(0));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/administrator")
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/members")
                .then()
                .statusCode(200)
                .body("Members", hasSize(0));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/invitations")
                .then()
                .statusCode(200)
                .body("Invitations", hasSize(0));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/invitations/count")
                .then()
                .statusCode(200)
                .body("InvitationsCount", equalTo(0));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/organization/admin")
                .then()
                .statusCode(200)
                .body("AdminAccounts", hasSize(0));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/organization/configuration")
                .then()
                .statusCode(200)
                .body("AutoEnable", equalTo(false));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/accounts")
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/accounts")
                .then()
                .statusCode(401)
                .body("__type", equalTo("InvalidAccessException"));
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region + "/securityhub/aws4_request";
    }
}
