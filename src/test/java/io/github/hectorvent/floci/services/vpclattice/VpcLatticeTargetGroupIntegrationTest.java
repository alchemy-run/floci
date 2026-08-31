package io.github.hectorvent.floci.services.vpclattice;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * Verifies VPC Lattice restJson1 target-group operations used by Alchemy
 * {@code Bindings.test.ts}: list/create/get, register/list/deregister Lambda
 * targets, tags, and delete.
 */
@QuarkusTest
class VpcLatticeTargetGroupIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String LAMBDA_ARN =
            "arn:aws:lambda:us-east-1:000000000000:function:lattice-bindings-fn";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getTargetGroupOnANonexistentIdFailsWithResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth(EAST))
                .when()
                .get("/targetgroups/tg-0123456789abcdef0")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("resourceType", equalTo("TARGET_GROUP"));
    }

    @Test
    void lambdaTargetGroupCreateListRegisterDeregisterDeleteLifecycle() {
        String authorization = auth(EAST);
        String name = "alchemy-test-lattice-tg-" + UUID.randomUUID().toString().substring(0, 8);

        String id = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "name":"%s",
                          "type":"LAMBDA",
                          "tags":{"fixture":"vpc-lattice","alchemy::id":"Live"}
                        }
                        """.formatted(name))
                .when()
                .post("/targetgroups")
                .then()
                .statusCode(200)
                .body("id", startsWith("tg-"))
                .body("arn", startsWith("arn:aws:vpc-lattice:" + EAST + ":"))
                .body("name", equalTo(name))
                .body("type", equalTo("LAMBDA"))
                .body("status", equalTo("ACTIVE"))
                .extract()
                .path("id");

        given()
                .header("Authorization", authorization)
                .when()
                .get("/targetgroups/" + id)
                .then()
                .statusCode(200)
                .body("id", equalTo(id))
                .body("name", equalTo(name))
                .body("type", equalTo("LAMBDA"))
                .body("status", equalTo("ACTIVE"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/targetgroups")
                .then()
                .statusCode(200)
                .body("items.name", hasItem(name));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .post("/targetgroups/" + id + "/listtargets")
                .then()
                .statusCode(200)
                .body("items", hasSize(0));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"targets\":[{\"id\":\"" + LAMBDA_ARN + "\"}]}")
                .when()
                .post("/targetgroups/" + id + "/registertargets")
                .then()
                .statusCode(200)
                .body("successful.id", hasItem(LAMBDA_ARN))
                .body("unsuccessful", hasSize(0));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .post("/targetgroups/" + id + "/listtargets")
                .then()
                .statusCode(200)
                .body("items", hasSize(1))
                .body("items[0].id", equalTo(LAMBDA_ARN))
                .body("items[0].status", equalTo("HEALTHY"));

        String arn = "arn:aws:vpc-lattice:" + EAST + ":000000000000:targetgroup/" + id;
        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"tags\":{\"extra\":\"yes\"}}")
                .when()
                .post("/tags/" + arn)
                .then()
                .statusCode(204);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/tags/" + arn)
                .then()
                .statusCode(200)
                .body("tags.fixture", equalTo("vpc-lattice"))
                .body("tags.extra", equalTo("yes"))
                .body("tags.'alchemy::id'", equalTo("Live"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"targets\":[{\"id\":\"" + LAMBDA_ARN + "\"}]}")
                .when()
                .post("/targetgroups/" + id + "/deregistertargets")
                .then()
                .statusCode(200)
                .body("successful.id", hasItem(LAMBDA_ARN))
                .body("unsuccessful", hasSize(0));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .post("/targetgroups/" + id + "/listtargets")
                .then()
                .statusCode(200)
                .body("items", hasSize(0));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/targetgroups/" + id)
                .then()
                .statusCode(200)
                .body("id", equalTo(id))
                .body("status", equalTo("DELETE_IN_PROGRESS"))
                .body("arn", notNullValue());

        given()
                .header("Authorization", authorization)
                .when()
                .get("/targetgroups/" + id)
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"));
    }

    private static String auth(String region) {
        return "AWS4-HMAC-SHA256 Credential=AKID/20260205/" + region + "/vpc-lattice/aws4_request";
    }
}
