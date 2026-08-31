package io.github.hectorvent.floci.services.detective;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies Detective restJson1 graph lifecycle and the operations Alchemy
 * {@code Bindings.test.ts} drives through the Lambda fixture.
 */
@QuarkusTest
class DetectiveIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String ACCOUNT = "000000000501";
    private static final String ROLE = "arn:aws:iam::000000000501:role/DetectiveBindings";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void listGraphsOnAFreshAccountIsEmpty() {
        invoke(auth("000000000502", EAST), "/graphs/list", "{}")
                .then()
                .statusCode(200)
                .body("GraphList", hasSize(0));
    }

    @Test
    void createGraphWhenOneAlreadyExistsConflicts() {
        String authorization = auth("000000000503", EAST);
        create(authorization);
        invoke(authorization, "/graph", "{\"Tags\":{\"env\":\"second\"}}")
                .then()
                .statusCode(409)
                .body("__type", equalTo("ConflictException"));
    }

    @Test
    void deleteGraphOnAMissingGraphIsResourceNotFound() {
        invoke(auth("000000000504", EAST), "/graph/removal",
                "{\"GraphArn\":\"arn:aws:detective:us-east-1:000000000504:graph:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void bindingsGraphMembersDatasourcesInvestigationsOrgAndTags() {
        String authorization = auth(ACCOUNT, EAST);
        String arn = create(authorization, """
                {"Tags":{"fixture":"detective-bindings","alchemy::id":"BindingsGraph"}}
                """);
        assertTrue(arn.contains(":graph:"));

        invoke(authorization, "/graphs/list", "{}")
                .then()
                .statusCode(200)
                .body("GraphList", hasSize(1))
                .body("GraphList[0].Arn", equalTo(arn))
                .body("GraphList[0].CreatedTime", notNullValue());

        Map<String, String> tags = given()
                .header("Authorization", authorization)
                .when()
                .get("/tags/" + encode(arn))
                .then()
                .statusCode(200)
                .extract()
                .path("Tags");
        assertEquals("detective-bindings", tags.get("fixture"));
        assertEquals("BindingsGraph", tags.get("alchemy::id"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"Tags\":{\"team\":\"security\"}}")
                .when()
                .post("/tags/" + encode(arn))
                .then()
                .statusCode(204);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/tags/" + encode(arn))
                .then()
                .statusCode(200)
                .body("Tags.team", equalTo("security"))
                .body("Tags.fixture", equalTo("detective-bindings"));

        invoke(authorization, "/graph/members/list", "{\"GraphArn\":\"" + arn + "\"}")
                .then()
                .statusCode(200)
                .body("MemberDetails", hasSize(0));

        invoke(authorization, "/graph/members/get",
                "{\"GraphArn\":\"" + arn + "\",\"AccountIds\":[\"123456789012\"]}")
                .then()
                .statusCode(200)
                .body("MemberDetails", hasSize(0))
                .body("UnprocessedAccounts", hasSize(1))
                .body("UnprocessedAccounts[0].AccountId", equalTo("123456789012"));

        invoke(authorization, "/graph/datasources/list", "{\"GraphArn\":\"" + arn + "\"}")
                .then()
                .statusCode(200)
                .body("DatasourcePackages", hasKey("DETECTIVE_CORE"))
                .body("DatasourcePackages.DETECTIVE_CORE.DatasourcePackageIngestState", equalTo("STARTED"));

        invoke(authorization, "/graph/datasources/get",
                "{\"GraphArn\":\"" + arn + "\",\"AccountIds\":[\"123456789012\"]}")
                .then()
                .statusCode(200)
                .body("MemberDatasources", hasSize(0))
                .body("UnprocessedAccounts", hasSize(1));

        invoke(authorization, "/investigations/listInvestigations", "{\"GraphArn\":\"" + arn + "\"}")
                .then()
                .statusCode(200)
                .body("InvestigationDetails", hasSize(0));

        String start = Instant.now().minus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS).toString();
        String end = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
        String investigationId = invoke(authorization, "/investigations/startInvestigation", """
                {
                  "GraphArn":"%s",
                  "EntityArn":"%s",
                  "ScopeStartTime":"%s",
                  "ScopeEndTime":"%s"
                }
                """.formatted(arn, ROLE, start, end))
                .then()
                .statusCode(200)
                .body("InvestigationId", notNullValue())
                .extract()
                .path("InvestigationId");
        assertTrue(investigationId.length() > 8);

        invoke(authorization, "/invitations/list", "{}")
                .then()
                .statusCode(200)
                .body("Invitations", hasSize(0));

        invoke(authorization, "/membership/datasources/get",
                "{\"GraphArns\":[\"" + arn + "\"]}")
                .then()
                .statusCode(200)
                .body("MembershipDatasources", hasSize(0));

        invoke(authorization, "/orgs/describeOrganizationConfiguration",
                "{\"GraphArn\":\"" + arn + "\"}")
                .then()
                .statusCode(200)
                .body("AutoEnable", equalTo(false));

        invoke(authorization, "/orgs/adminAccountslist", "{}")
                .then()
                .statusCode(200)
                .body("Administrators", hasSize(0));

        invoke(authorization, "/graph/removal", "{\"GraphArn\":\"" + arn + "\"}")
                .then()
                .statusCode(200);

        invoke(authorization, "/graphs/list", "{}")
                .then()
                .statusCode(200)
                .body("GraphList", hasSize(0));
    }

    private static String create(String authorization) {
        return create(authorization, "{\"Tags\":{\"fixture\":\"detective\"}}");
    }

    private static String create(String authorization, String body) {
        return invoke(authorization, "/graph", body)
                .then()
                .statusCode(200)
                .body("GraphArn", startsWith("arn:aws:detective:"))
                .extract()
                .path("GraphArn");
    }

    private static Response invoke(String authorization, String path, String body) {
        return given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body(body)
                .when()
                .post(path);
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region + "/detective/aws4_request";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
