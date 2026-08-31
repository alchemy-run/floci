package io.github.hectorvent.floci.services.ram;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Resource-share lifecycle used by Alchemy {@code ResourceShare.test.ts}:
 * create with an external principal and tags, list, associate/disassociate,
 * tag in place, then delete.
 */
@QuarkusTest
class RamResourceShareIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String ACCOUNT = "000000002401";
    private static final String EXTERNAL = "123456789012";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void createAssociateTagDisassociateAndDelete() {
        String authorization = auth(ACCOUNT, EAST);

        Response created = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "name":"resource-share-fixture",
                          "allowExternalPrincipals":true,
                          "principals":["%s"],
                          "tags":[
                            {"key":"team","value":"platform"},
                            {"key":"alchemy::id","value":"TestShare"}
                          ]
                        }
                        """.formatted(EXTERNAL))
                .when()
                .post("/createresourceshare");
        created.then()
                .statusCode(200)
                .body("resourceShare.resourceShareArn", notNullValue())
                .body("resourceShare.allowExternalPrincipals", equalTo(true))
                .body("resourceShare.status", equalTo("ACTIVE"))
                .body("resourceShare.tags.key", hasItem("team"))
                .body("resourceShare.tags.key", hasItem("alchemy::id"));
        String arn = created.jsonPath().getString("resourceShare.resourceShareArn");
        assertTrue(arn.startsWith("arn:aws:ram:" + EAST + ":" + ACCOUNT + ":resource-share/"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"resourceOwner\":\"SELF\",\"resourceShareArns\":[\"" + arn + "\"]}")
                .when()
                .post("/getresourceshares")
                .then()
                .statusCode(200)
                .body("resourceShares.size()", equalTo(1))
                .body("resourceShares[0].resourceShareArn", equalTo(arn))
                .body("resourceShares[0].tags.key", hasItem("team"));

        List<Map<String, Object>> principals = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"associationType\":\"PRINCIPAL\",\"resourceShareArns\":[\"" + arn + "\"]}")
                .when()
                .post("/getresourceshareassociations")
                .then()
                .statusCode(200)
                .extract()
                .path("resourceShareAssociations");
        assertTrue(principals.stream().anyMatch(association ->
                EXTERNAL.equals(association.get("associatedEntity"))
                        && ("ASSOCIATED".equals(association.get("status"))
                                || "ASSOCIATING".equals(association.get("status")))));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"resourceShareArn\":\"" + arn
                        + "\",\"tags\":[{\"key\":\"env\",\"value\":\"prod\"}]}")
                .when()
                .post("/tagresource")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"resourceOwner\":\"SELF\",\"resourceShareArns\":[\"" + arn + "\"]}")
                .when()
                .post("/getresourceshares")
                .then()
                .statusCode(200)
                .body("resourceShares[0].tags.key", hasItem("env"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"resourceShareArn\":\"" + arn + "\",\"principals\":[\"" + EXTERNAL + "\"]}")
                .when()
                .post("/disassociateresourceshare")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"associationType\":\"PRINCIPAL\",\"resourceShareArns\":[\"" + arn + "\"]}")
                .when()
                .post("/getresourceshareassociations")
                .then()
                .statusCode(200)
                .body("resourceShareAssociations.associatedEntity", not(hasItem(EXTERNAL)));

        List<Map<String, Object>> listed = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"resourceOwner\":\"SELF\"}")
                .when()
                .post("/getresourceshares")
                .then()
                .statusCode(200)
                .extract()
                .path("resourceShares");
        assertTrue(listed.stream().anyMatch(share -> arn.equals(share.get("resourceShareArn"))));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/deleteresourceshare?resourceShareArn=" + arn)
                .then()
                .statusCode(200)
                .body("returnValue", equalTo(true));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"resourceOwner\":\"SELF\",\"resourceShareArns\":[\"" + arn + "\"]}")
                .when()
                .post("/getresourceshares")
                .then()
                .statusCode(400)
                .body("__type", equalTo("UnknownResourceException"));
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region
                + "/ram/aws4_request";
    }
}
