package io.github.hectorvent.floci.services.rbin;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Verifies Recycle Bin restJson1 operations used by Alchemy
 * {@code Bindings.test.ts}: GetRule (injected identifier) and ListRules
 * (Region-wide, filtered by resource type).
 */
@QuarkusTest
class RbinBindingsIntegrationTest {

    private static final String EAST = "us-east-1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getRuleAndListRulesMatchAlchemyBindingFixture() {
        String authorization = auth(EAST);
        String identifier = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "ResourceType":"EBS_SNAPSHOT",
                          "RetentionPeriod":{"RetentionPeriodValue":7,"RetentionPeriodUnit":"DAYS"},
                          "Description":"alchemy rbin bindings fixture rule",
                          "ResourceTags":[{"ResourceTagKey":"AlchemyRbinBindings","ResourceTagValue":"true"}]
                        }
                        """)
                .when()
                .post("/rules")
                .then()
                .statusCode(200)
                .body("Identifier", notNullValue())
                .extract()
                .path("Identifier");

        given()
                .header("Authorization", authorization)
                .when()
                .get("/rules/" + identifier)
                .then()
                .statusCode(200)
                .body("Identifier", equalTo(identifier))
                .body("ResourceType", equalTo("EBS_SNAPSHOT"))
                .body("RetentionPeriod.RetentionPeriodValue", equalTo(7))
                .body("Description", equalTo("alchemy rbin bindings fixture rule"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"ResourceType\":\"EBS_SNAPSHOT\"}")
                .when()
                .post("/list-rules")
                .then()
                .statusCode(200)
                .body("Rules.Identifier", hasItem(identifier));
    }

    private static String auth(String region) {
        return "AWS4-HMAC-SHA256 Credential=AKID/20260205/" + region + "/rbin/aws4_request";
    }
}
