package io.github.hectorvent.floci.services.oam;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the OAM restJson1 sink lifecycle and ListAttachedLinks used by Alchemy bindings. */
@QuarkusTest
class OamIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String ACCOUNT = "000000002401";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getSinkOnANonexistentArnFailsWithResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth(ACCOUNT, EAST))
                .body("{\"Identifier\":\"arn:aws:oam:us-east-1:000000002401:sink/00000000-0000-0000-0000-000000000000\"}")
                .when()
                .post("/GetSink")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void sinkCreateGetListTagsAttachedLinksDeleteLifecycle() {
        String authorization = auth(ACCOUNT, EAST);
        String name = "lifecycle-sink";

        String arn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "Name":"lifecycle-sink",
                          "Tags":{"Owner":"floci"}
                        }
                        """)
                .when()
                .post("/CreateSink")
                .then()
                .statusCode(200)
                .body("Arn", notNullValue())
                .body("Id", notNullValue())
                .body("Name", equalTo(name))
                .body("Tags.Owner", equalTo("floci"))
                .extract().path("Arn");
        String id = arn.substring(arn.lastIndexOf('/') + 1);
        assertTrue(arn.contains(":oam:" + EAST + ":" + ACCOUNT + ":sink/"));
        assertTrue(arn.endsWith("/" + id));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"Identifier\":\"" + arn + "\"}")
                .when()
                .post("/GetSink")
                .then()
                .statusCode(200)
                .body("Arn", equalTo(arn))
                .body("Id", equalTo(id))
                .body("Name", equalTo(name));

        List<Map<String, Object>> items = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/ListSinks")
                .then()
                .statusCode(200)
                .extract().path("Items");
        assertEquals(1, items.size());
        assertEquals(arn, items.get(0).get("Arn"));
        assertEquals(name, items.get(0).get("Name"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"SinkIdentifier\":\"" + arn + "\"}")
                .when()
                .post("/ListAttachedLinks")
                .then()
                .statusCode(200)
                .body("Items.size()", equalTo(0));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"Tags\":{\"purpose\":\"alchemy-test\"}}")
                .when()
                .put("/tags/" + encode(arn))
                .then()
                .statusCode(204);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/tags/" + encode(arn))
                .then()
                .statusCode(200)
                .body("Tags.Owner", equalTo("floci"))
                .body("Tags.purpose", equalTo("alchemy-test"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "SinkIdentifier":"%s",
                          "Policy":"{\\"Version\\":\\"2012-10-17\\",\\"Statement\\":[]}"
                        }
                        """.formatted(arn))
                .when()
                .post("/PutSinkPolicy")
                .then()
                .statusCode(200)
                .body("SinkArn", equalTo(arn))
                .body("SinkId", equalTo(id));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"SinkIdentifier\":\"" + arn + "\"}")
                .when()
                .post("/GetSinkPolicy")
                .then()
                .statusCode(200)
                .body("SinkArn", equalTo(arn))
                .body("Policy", notNullValue());

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "LabelTemplate":"$AccountName",
                          "ResourceTypes":["AWS::CloudWatch::Metric"],
                          "SinkIdentifier":"%s"
                        }
                        """.formatted(arn))
                .when()
                .post("/CreateLink")
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidParameterException"))
                .body("message", org.hamcrest.Matchers.containsString("same account"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"Name\":\"second-sink\"}")
                .when()
                .post("/CreateSink")
                .then()
                .statusCode(409)
                .body("__type", equalTo("ConflictException"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"Identifier\":\"" + arn + "\"}")
                .when()
                .post("/DeleteSink")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"Identifier\":\"" + arn + "\"}")
                .when()
                .post("/GetSink")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region
                + "/oam/aws4_request";
    }

    private static String encode(String arn) {
        return URLEncoder.encode(arn, StandardCharsets.UTF_8);
    }
}
