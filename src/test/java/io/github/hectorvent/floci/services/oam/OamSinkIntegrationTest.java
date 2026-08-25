package io.github.hectorvent.floci.services.oam;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies OAM restJson1 sink operations used by Alchemy {@code Sink.test.ts}:
 * ListSinks of an empty account, create/get/policy/tags, same-account CreateLink
 * rejection, and delete.
 */
@QuarkusTest
class OamSinkIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String ACCOUNT = "000000000301";
    private static final String POLICY = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"AWS":["000000000301"]},\
            "Action":["oam:CreateLink","oam:UpdateLink"],"Resource":"*","Condition":{\
            "ForAllValues:StringEquals":{"oam:ResourceTypes":["AWS::CloudWatch::Metric"]}}}]}
            """;
    private static final String UPDATED_POLICY = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"AWS":["000000000301"]},\
            "Action":["oam:CreateLink","oam:UpdateLink"],"Resource":"*","Condition":{\
            "ForAllValues:StringEquals":{"oam:ResourceTypes":["AWS::CloudWatch::Metric","AWS::Logs::LogGroup"]}}}]}
            """;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getSinkOnANonexistentArnFailsWithResourceNotFoundException() {
        String arn = "arn:aws:oam:" + EAST + ":" + ACCOUNT + ":sink/00000000-0000-0000-0000-000000000000";
        given()
                .contentType("application/json")
                .header("Authorization", auth(ACCOUNT, EAST))
                .body("{\"Identifier\":\"" + arn + "\"}")
                .when()
                .post("/GetSink")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void listSinksOnAnEmptyAccountReturnsNoItems() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000000399", EAST))
                .body("{}")
                .when()
                .post("/ListSinks")
                .then()
                .statusCode(200)
                .body("Items.size()", equalTo(0));
    }

    @Test
    void sinkCreatePolicyTagsAndSameAccountLinkRejectionLifecycle() {
        String authorization = auth(ACCOUNT, EAST);

        Map<String, Object> created = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "Name":"MonitoringSink",
                          "Tags":{"purpose":"alchemy-test","alchemy::id":"MonitoringSink"}
                        }
                        """)
                .when()
                .post("/CreateSink")
                .then()
                .statusCode(200)
                .body("Arn", containsString(":sink/"))
                .body("Id", notNullValue())
                .body("Name", equalTo("MonitoringSink"))
                .body("Tags.purpose", equalTo("alchemy-test"))
                .extract()
                .jsonPath()
                .getMap(".");

        String arn = (String) created.get("Arn");
        String id = (String) created.get("Id");
        assertEquals(true, arn.contains(id));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"Identifier\":\"" + arn + "\"}")
                .when()
                .post("/GetSink")
                .then()
                .statusCode(200)
                .body("Name", equalTo("MonitoringSink"))
                .body("Arn", equalTo(arn))
                .body("Id", equalTo(id));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/ListSinks")
                .then()
                .statusCode(200)
                .body("Items[0].Arn", equalTo(arn))
                .body("Items[0].Name", equalTo("MonitoringSink"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"SinkIdentifier\":\"" + arn + "\",\"Policy\":" + quote(POLICY) + "}")
                .when()
                .post("/PutSinkPolicy")
                .then()
                .statusCode(200)
                .body("SinkArn", equalTo(arn))
                .body("Policy", containsString("oam:CreateLink"))
                .body("Policy", containsString("AWS::CloudWatch::Metric"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"SinkIdentifier\":\"" + arn + "\"}")
                .when()
                .post("/GetSinkPolicy")
                .then()
                .statusCode(200)
                .body("Policy", containsString("oam:CreateLink"))
                .body("Policy", containsString("AWS::CloudWatch::Metric"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/tags/" + arn)
                .then()
                .statusCode(200)
                .body("Tags.purpose", equalTo("alchemy-test"))
                .body("Tags.'alchemy::id'", equalTo("MonitoringSink"));

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
                .body("message", containsString("same account"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"SinkIdentifier\":\"" + arn + "\",\"Policy\":" + quote(UPDATED_POLICY) + "}")
                .when()
                .post("/PutSinkPolicy")
                .then()
                .statusCode(200)
                .body("Policy", containsString("AWS::Logs::LogGroup"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"Tags\":{\"updated\":\"true\"}}")
                .when()
                .put("/tags/" + arn)
                .then()
                .statusCode(204);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/tags/" + arn)
                .then()
                .statusCode(200)
                .body("Tags.updated", equalTo("true"))
                .body("Tags.purpose", equalTo("alchemy-test"));

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

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"Identifier\":\"" + arn + "\"}")
                .when()
                .post("/DeleteSink")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "") + "\"";
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region + "/oam/aws4_request";
    }
}
