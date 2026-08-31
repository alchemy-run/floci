package io.github.hectorvent.floci.services.qapps;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Verifies the Q Apps restJson1 GetQApp / DeleteQApp / CreateQApp lifecycle. */
@QuarkusTest
class QAppsIntegrationTest {

    private static final String INSTANCE = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    private static final String MISSING_APP = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    private static final String EAST = "us-east-1";
    private static final String TEXT_CARD = "11111111-1111-4111-8111-111111111111";
    private static final String QUERY_CARD = "22222222-2222-4222-8222-222222222222";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getQAppAgainstMissingAppReturnsResourceNotFound() {
        given()
                .header("Authorization", auth("000000000201", EAST))
                .header("instance-id", INSTANCE)
                .queryParam("appId", MISSING_APP)
                .when()
                .get("/apps.get")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", "ResourceNotFoundException")
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("resourceId", equalTo(MISSING_APP))
                .body("resourceType", equalTo("QApp"));
    }

    @Test
    void deleteQAppAgainstMissingAppReturnsResourceNotFound() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000000202", EAST))
                .header("instance-id", INSTANCE)
                .body("{\"appId\":\"" + MISSING_APP + "\"}")
                .when()
                .post("/apps.delete")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", "ResourceNotFoundException")
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("resourceId", equalTo(MISSING_APP))
                .body("resourceType", equalTo("QApp"));
    }

    @Test
    void createGetUpdateListDeleteLifecycle() {
        String authorization = auth("000000000203", EAST);
        String appId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .header("instance-id", INSTANCE)
                .body(createBody("Summarize the following text: @Source Text", null))
                .when()
                .post("/apps.create")
                .then()
                .statusCode(200)
                .body("appId", notNullValue())
                .body("appArn", notNullValue())
                .body("title", equalTo("Summarizer"))
                .body("appVersion", equalTo(1))
                .body("status", equalTo("PUBLISHED"))
                .extract()
                .path("appId");

        given()
                .header("Authorization", authorization)
                .header("instance-id", INSTANCE)
                .queryParam("appId", appId)
                .when()
                .get("/apps.get")
                .then()
                .statusCode(200)
                .body("appId", equalTo(appId))
                .body("appDefinition.cards.size()", equalTo(2))
                .body("appDefinition.cards[1].qQuery.prompt",
                        equalTo("Summarize the following text: @Source Text"))
                .body("appDefinition.cards[1].qQuery.dependencies[0]", equalTo(TEXT_CARD))
                .body("appVersion", equalTo(1));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .header("instance-id", INSTANCE)
                .body(createBody(
                        "Summarize the following text in one sentence: @Source Text",
                        appId,
                        "updated by test"))
                .when()
                .post("/apps.update")
                .then()
                .statusCode(200)
                .body("appId", equalTo(appId))
                .body("description", equalTo("updated by test"))
                .body("appVersion", greaterThan(1));

        Response listed = given()
                .header("Authorization", authorization)
                .header("instance-id", INSTANCE)
                .when()
                .get("/apps.list")
                .then()
                .statusCode(200)
                .extract()
                .response();
        assertEquals(appId, listed.path("apps[0].appId"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .header("instance-id", INSTANCE)
                .body("{\"appId\":\"" + appId + "\"}")
                .when()
                .post("/apps.delete")
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .header("instance-id", INSTANCE)
                .queryParam("appId", appId)
                .when()
                .get("/apps.get")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    private static String createBody(String prompt, String appId) {
        return createBody(prompt, appId, null);
    }

    private static String createBody(String prompt, String appId, String description) {
        StringBuilder body = new StringBuilder("{");
        if (appId != null) {
            body.append("\"appId\":\"").append(appId).append("\",");
        }
        body.append("\"title\":\"Summarizer\",");
        if (description != null) {
            body.append("\"description\":\"").append(description).append("\",");
        }
        body.append("\"appDefinition\":{\"cards\":[")
                .append("{\"textInput\":{\"id\":\"")
                .append(TEXT_CARD)
                .append("\",\"title\":\"Source Text\",\"type\":\"text-input\"}},")
                .append("{\"qQuery\":{\"id\":\"")
                .append(QUERY_CARD)
                .append("\",\"title\":\"Summary\",\"type\":\"q-query\",\"prompt\":\"")
                .append(prompt)
                .append("\"}}")
                .append("]}}");
        return body.toString();
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region + "/qapps/aws4_request";
    }
}
