package io.github.hectorvent.floci.services.qbusiness;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Alchemy {@code test/AWS/QBusiness/Bindings.test.ts}: every binding operation
 * against a nonexistent application yields a typed
 * {@code ResourceNotFoundException}, except {@code ListSubscriptions} which
 * answers an empty page.
 */
@QuarkusTest
class QBusinessBindingsIntegrationTest {

    private static final String ACCOUNT = "000000000821";
    private static final String REGION = "us-east-1";
    private static final String MISSING = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void chatSyncAgainstMissingApplicationYieldsResourceNotFound() {
        given()
                .contentType("application/json")
                .header("Authorization", auth())
                .body("{\"userMessage\":\"probe\"}")
                .queryParam("sync")
                .when()
                .post("/applications/" + MISSING + "/conversations")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("resourceType", equalTo("Application"));
    }

    @Test
    void listSubscriptionsAgainstMissingApplicationAnswersEmptyPage() {
        given()
                .header("Authorization", auth())
                .when()
                .get("/applications/" + MISSING + "/subscriptions")
                .then()
                .statusCode(200)
                .body("subscriptions", hasSize(0));
    }

    @Test
    void bindingProbesAgainstMissingApplicationYieldResourceNotFound() {
        assertNotFound("POST", "/applications/" + MISSING + "/relevant-content",
                "{\"queryText\":\"probe\",\"contentSource\":{\"retriever\":{\"retrieverId\":\""
                        + MISSING + "\"}}}");
        assertNotFound("POST", "/applications/" + MISSING + "/conversations/" + MISSING
                + "/messages/" + MISSING + "/feedback",
                "{\"messageUsefulness\":{\"usefulness\":\"USEFUL\"}}");
        assertNotFound("GET", "/applications/" + MISSING + "/chatcontrols", null);
        assertNotFound("PATCH", "/applications/" + MISSING + "/chatcontrols",
                "{\"responseScope\":\"ENTERPRISE_CONTENT_ONLY\"}");
        assertNotFound("DELETE", "/applications/" + MISSING + "/chatcontrols", null);
        assertNotFound("GET", "/applications/" + MISSING + "/conversations", null);
        assertNotFound("DELETE", "/applications/" + MISSING + "/conversations/" + MISSING, null);
        assertNotFound("GET", "/applications/" + MISSING + "/conversations/" + MISSING, null);
        assertNotFound("GET", "/applications/" + MISSING + "/attachments", null);
        assertNotFound("DELETE", "/applications/" + MISSING + "/conversations/" + MISSING
                + "/attachments/" + MISSING, null);
        assertNotFound("GET", "/applications/" + MISSING + "/conversations/" + MISSING
                + "/messages/" + MISSING + "/media/" + MISSING, null);
        assertNotFound("POST", "/applications/" + MISSING + "/users",
                "{\"userId\":\"probe@example.com\"}");
        assertNotFound("GET", "/applications/" + MISSING + "/users/probe@example.com", null);
        assertNotFound("PUT", "/applications/" + MISSING + "/users/probe@example.com", "{}");
        assertNotFound("DELETE", "/applications/" + MISSING + "/users/probe@example.com", null);
        assertNotFound("GET", "/applications/" + MISSING + "/policy", null);
        assertNotFound("POST", "/applications/" + MISSING + "/policy",
                "{\"statementId\":\"probe\",\"actions\":[\"qbusiness:SearchRelevantContent\"],"
                        + "\"principal\":\"arn:aws:iam::123456789012:role/AlchemyProbeRole\"}");
        assertNotFound("DELETE", "/applications/" + MISSING + "/policy/probe", null);
        assertNotFound("POST", "/applications/" + MISSING + "/subscriptions",
                "{\"principal\":{\"user\":\"" + MISSING + "\"},\"type\":\"Q_LITE\"}");
        assertNotFound("PUT", "/applications/" + MISSING + "/subscriptions/" + MISSING,
                "{\"type\":\"Q_LITE\"}");
        assertNotFound("DELETE", "/applications/" + MISSING + "/subscriptions/" + MISSING, null);
        assertNotFound("POST", "/applications/" + MISSING + "/indices/" + MISSING + "/documents",
                "{\"documents\":[{\"id\":\"probe\",\"contentType\":\"PLAIN_TEXT\"}]}");
        assertNotFound("POST", "/applications/" + MISSING + "/indices/" + MISSING + "/documents/delete",
                "{\"documents\":[{\"documentId\":\"probe\"}]}");
        assertNotFound("GET", "/applications/" + MISSING + "/index/" + MISSING + "/documents", null);
        assertNotFound("GET", "/applications/" + MISSING + "/index/" + MISSING
                + "/documents/probe/content", null);
        assertNotFound("GET", "/applications/" + MISSING + "/index/" + MISSING
                + "/users/probe@example.com/documents/probe/check-document-access", null);
        assertNotFound("PUT", "/applications/" + MISSING + "/indices/" + MISSING + "/groups",
                "{\"groupName\":\"probe\",\"type\":\"INDEX\","
                        + "\"groupMembers\":{\"memberUsers\":[{\"userId\":\"probe@example.com\",\"type\":\"INDEX\"}]}}");
        assertNotFound("GET", "/applications/" + MISSING + "/indices/" + MISSING + "/groups/probe", null);
        assertNotFound("DELETE", "/applications/" + MISSING + "/indices/" + MISSING + "/groups/probe", null);
        assertNotFound("GET", "/applications/" + MISSING + "/indices/" + MISSING + "/groups", null);
        assertNotFound("POST", "/applications/" + MISSING + "/indices/" + MISSING
                + "/datasources/" + MISSING + "/startsync", "{}");
        assertNotFound("POST", "/applications/" + MISSING + "/indices/" + MISSING
                + "/datasources/" + MISSING + "/stopsync", "{}");
        assertNotFound("GET", "/applications/" + MISSING + "/indices/" + MISSING
                + "/datasources/" + MISSING + "/syncjobs", null);
        assertNotFound("POST", "/applications/" + MISSING + "/experiences/" + MISSING + "/anonymous-url",
                "{}");
    }

    @Test
    void applicationIndexDocumentChatAndSubscriptionsRoundTrip() {
        String applicationId = given()
                .contentType("application/json")
                .header("Authorization", auth())
                .body("{\"displayName\":\"AlchemyQBusinessBindings\",\"identityType\":\"ANONYMOUS\"}")
                .when()
                .post("/applications")
                .then()
                .statusCode(200)
                .body("applicationId", notNullValue())
                .extract().path("applicationId");

        given()
                .header("Authorization", auth())
                .when()
                .get("/applications/" + applicationId)
                .then()
                .statusCode(200)
                .body("displayName", equalTo("AlchemyQBusinessBindings"))
                .body("status", equalTo("ACTIVE"))
                .body("identityType", equalTo("ANONYMOUS"));

        String indexId = given()
                .contentType("application/json")
                .header("Authorization", auth())
                .body("{\"displayName\":\"Docs\",\"type\":\"STARTER\"}")
                .when()
                .post("/applications/" + applicationId + "/indices")
                .then()
                .statusCode(200)
                .body("indexId", notNullValue())
                .extract().path("indexId");

        String blob = Base64.getEncoder().encodeToString(
                "The zanzibar passphrase is quicksilver.".getBytes(StandardCharsets.UTF_8));
        given()
                .contentType("application/json")
                .header("Authorization", auth())
                .body("{\"documents\":[{\"id\":\"welcome\",\"title\":\"Welcome\","
                        + "\"content\":{\"blob\":\"" + blob + "\"},\"contentType\":\"PLAIN_TEXT\"}]}")
                .when()
                .post("/applications/" + applicationId + "/indices/" + indexId + "/documents")
                .then()
                .statusCode(200)
                .body("failedDocuments", hasSize(0));

        given()
                .header("Authorization", auth())
                .when()
                .get("/applications/" + applicationId + "/index/" + indexId + "/documents")
                .then()
                .statusCode(200)
                .body("documentDetailList[0].status", equalTo("INDEXED"));

        given()
                .contentType("application/json")
                .header("Authorization", auth())
                .queryParam("sync")
                .body("{\"userMessage\":\"zanzibar passphrase\"}")
                .when()
                .post("/applications/" + applicationId + "/conversations")
                .then()
                .statusCode(200)
                .body("conversationId", notNullValue())
                .body("systemMessage", notNullValue());

        given()
                .header("Authorization", auth())
                .when()
                .get("/applications/" + applicationId + "/subscriptions")
                .then()
                .statusCode(200)
                .body("subscriptions", hasSize(0));

        given()
                .header("Authorization", auth())
                .when()
                .delete("/applications/" + applicationId)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", auth())
                .when()
                .get("/applications/" + applicationId)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    private static void assertNotFound(String method, String path, String body) {
        var request = given().header("Authorization", auth());
        if (body != null) {
            request = request.contentType("application/json").body(body);
        }
        var then = switch (method) {
            case "GET" -> request.when().get(path).then();
            case "POST" -> request.when().post(path).then();
            case "PUT" -> request.when().put(path).then();
            case "PATCH" -> request.when().patch(path).then();
            case "DELETE" -> request.when().delete(path).then();
            default -> throw new IllegalArgumentException(method);
        };
        then.statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    private static String auth() {
        return "AWS4-HMAC-SHA256 Credential=" + ACCOUNT + "/20260205/" + REGION
                + "/qbusiness/aws4_request";
    }
}
