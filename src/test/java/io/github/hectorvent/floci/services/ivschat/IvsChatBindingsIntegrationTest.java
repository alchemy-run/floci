package io.github.hectorvent.floci.services.ivschat;

import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;

/**
 * Verifies IVS Chat data-plane operations used by Alchemy
 * {@code Bindings.test.ts}: CreateChatToken honors sessionDurationInMinutes,
 * SendEvent / DeleteMessage return ids, DisconnectUser is a no-op success,
 * and the messaging websocket delivers ALLOW / 406 DENY review verdicts.
 */
@QuarkusTest
class IvsChatBindingsIntegrationTest {

    private static final String EAST = "us-east-1";

    @InjectMock
    LambdaService lambdaService;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void createChatTokenHonorsSessionDurationAndMintsAToken() {
        String authorization = auth(EAST);
        String arn = createRoom(authorization, "bindings-token-" + id());

        var body = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "roomIdentifier":"%s",
                          "userId":"alchemy-test-user",
                          "capabilities":["SEND_MESSAGE"],
                          "sessionDurationInMinutes":30,
                          "attributes":{"displayName":"Alchemy"}
                        }
                        """.formatted(arn))
                .when()
                .post("/CreateChatToken")
                .then()
                .statusCode(200)
                .body("token", notNullValue())
                .body("tokenExpirationTime", notNullValue())
                .body("sessionExpirationTime", notNullValue())
                .extract()
                .body();

        String token = body.jsonPath().getString("token");
        assertTrue(token.startsWith("ivschat."));
        assertTrue(token.length() > 8);
        Instant session = Instant.parse(body.jsonPath().getString("sessionExpirationTime"));
        long minutes = Duration.between(Instant.now(), session).toMinutes();
        assertTrue(minutes > 20 && minutes < 40, "session duration was " + minutes + " minutes");
    }

    @Test
    void createChatTokenAcceptsTheRegionalAwsHostHeader() {
        String authorization = auth(EAST);
        String arn = createRoom(authorization, "bindings-token-host-" + id());

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .header("Host", "ivschat.us-east-1.amazonaws.com")
                .body("""
                        {
                          "roomIdentifier":"%s",
                          "userId":"alchemy-test-user",
                          "capabilities":["SEND_MESSAGE"],
                          "sessionDurationInMinutes":30
                        }
                        """.formatted(arn))
                .when()
                .post("/CreateChatToken")
                .then()
                .statusCode(200)
                .body("token", notNullValue());
    }

    @Test
    void sendEventAndDeleteMessageReturnIds() {
        String authorization = auth(EAST);
        String arn = createRoom(authorization, "bindings-event-" + id());

        String eventId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {"roomIdentifier":"%s","eventName":"app:announcement","attributes":{"note":"hello"}}
                        """.formatted(arn))
                .when()
                .post("/SendEvent")
                .then()
                .statusCode(200)
                .body("id", notNullValue())
                .extract()
                .path("id");
        assertTrue(eventId.length() > 0);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {"roomIdentifier":"%s","id":"%s","reason":"moderated by alchemy test"}
                        """.formatted(arn, eventId))
                .when()
                .post("/DeleteMessage")
                .then()
                .statusCode(200)
                .body("id", equalTo(eventId));
    }

    @Test
    void disconnectUserSucceedsWhenTheUserHasNoConnections() {
        String authorization = auth(EAST);
        String arn = createRoom(authorization, "bindings-disconnect-" + id());

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {"roomIdentifier":"%s","userId":"alchemy-test-user","reason":"alchemy test"}
                        """.formatted(arn))
                .when()
                .post("/DisconnectUser")
                .then()
                .statusCode(200);
    }

    @Test
    void createChatTokenOnUnknownRoomIsResourceNotFound() {
        String arn = "arn:aws:ivschat:" + EAST + ":000000000000:room/AbCdEfGh1234";
        given()
                .contentType("application/json")
                .header("Authorization", auth(EAST))
                .body("""
                        {"roomIdentifier":"%s","userId":"alchemy-test-user"}
                        """.formatted(arn))
                .when()
                .post("/CreateChatToken")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"));
    }

    @Test
    void messagingWebsocketDeliversAllowedContent() throws Exception {
        String authorization = auth(EAST);
        String arn = createRoom(authorization, "bindings-ws-allow-" + id());
        String token = mintToken(authorization, arn);

        String frame = sendAndReceive(token, "hello moderators", Duration.ofSeconds(5));
        assertTrue(frame.contains("\"Type\":\"MESSAGE\""), frame);
        assertTrue(frame.contains("hello moderators"), frame);
    }

    @Test
    void messagingWebsocketDeliversReviewedContentFromHandler() throws Exception {
        stubReview("{\"ReviewResult\":\"ALLOW\",\"Content\":\"hello moderators [reviewed]\"}");
        String authorization = auth(EAST);
        String arn = createRoomWithHandler(authorization, "bindings-ws-review-allow-" + id(),
                "arn:aws:lambda:" + EAST + ":000000000000:function:review", "ALLOW");
        String token = mintToken(authorization, arn);

        String frame = sendAndReceive(token, "hello moderators", Duration.ofSeconds(5));
        assertTrue(frame.contains("\"Type\":\"MESSAGE\""), frame);
        assertTrue(frame.contains("hello moderators [reviewed]"), frame);
    }

    @Test
    void messagingWebsocketDeniesWithHandlerReason() throws Exception {
        stubReview("{\"ReviewResult\":\"DENY\",\"Attributes\":{\"Reason\":\"alchemy-moderated\"}}");
        String authorization = auth(EAST);
        String arn = createRoomWithHandler(authorization, "bindings-ws-review-deny-" + id(),
                "arn:aws:lambda:" + EAST + ":000000000000:function:review", "ALLOW");
        String token = mintToken(authorization, arn);

        String frame = sendAndReceive(token, "please deny-me now", Duration.ofSeconds(5));
        assertTrue(frame.contains("\"Type\":\"ERROR\""), frame);
        assertTrue(frame.contains("\"ErrorCode\":406"), frame);
        assertTrue(frame.contains("alchemy-moderated"), frame);
    }

    @Test
    void messagingWebsocketDeniesWhenReviewFallbackIsDeny() throws Exception {
        String authorization = auth(EAST);
        String arn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "name":"bindings-ws-deny-%s",
                          "messageReviewHandler":{
                            "uri":"arn:aws:lambda:%s:000000000000:function:missing-review",
                            "fallbackResult":"DENY"
                          }
                        }
                        """.formatted(id(), EAST))
                .when()
                .post("/CreateRoom")
                .then()
                .statusCode(200)
                .extract()
                .path("arn");
        String token = mintToken(authorization, arn);

        String frame = sendAndReceive(token, "please deny-me now", Duration.ofSeconds(5));
        assertTrue(frame.contains("\"Type\":\"ERROR\""), frame);
        assertTrue(frame.contains("\"ErrorCode\":406"), frame);
        assertTrue(frame.contains("\"ErrorMessage\""), frame);
    }

    private static String createRoom(String authorization, String name) {
        return given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"name\":\"" + name + "\"}")
                .when()
                .post("/CreateRoom")
                .then()
                .statusCode(200)
                .body("arn", containsString(":room/"))
                .extract()
                .path("arn");
    }

    private static String createRoomWithHandler(String authorization, String name, String uri, String fallback) {
        return given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "name":"%s",
                          "messageReviewHandler":{"uri":"%s","fallbackResult":"%s"}
                        }
                        """.formatted(name, uri, fallback))
                .when()
                .post("/CreateRoom")
                .then()
                .statusCode(200)
                .body("arn", containsString(":room/"))
                .extract()
                .path("arn");
    }

    private void stubReview(String payload) {
        InvokeResult result = new InvokeResult();
        result.setStatusCode(200);
        result.setPayload(payload.getBytes(StandardCharsets.UTF_8));
        Mockito.when(lambdaService.invoke(any(), any(), any(), any())).thenReturn(result);
    }

    private static String mintToken(String authorization, String arn) {
        return given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "roomIdentifier":"%s",
                          "userId":"alchemy-test-user",
                          "capabilities":["SEND_MESSAGE"],
                          "sessionDurationInMinutes":30
                        }
                        """.formatted(arn))
                .when()
                .post("/CreateChatToken")
                .then()
                .statusCode(200)
                .body("token", notNullValue())
                .extract()
                .path("token");
    }

    private static String sendAndReceive(String token, String content, Duration timeout) throws Exception {
        CompletableFuture<String> received = new CompletableFuture<>();
        HttpClient client = HttpClient.newHttpClient();
        WebSocket socket = client.newWebSocketBuilder()
                .subprotocols(token)
                .buildAsync(URI.create("ws://localhost:" + RestAssured.port + "/ivschat"),
                        new WebSocket.Listener() {
                            private final StringBuilder buffer = new StringBuilder();

                            @Override
                            public void onOpen(WebSocket webSocket) {
                                webSocket.sendText("""
                                        {"Action":"SEND_MESSAGE","RequestId":"alchemy-review-test","Content":"%s"}
                                        """.formatted(content), true);
                                webSocket.request(1);
                            }

                            @Override
                            public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                                buffer.append(data);
                                if (last) {
                                    received.complete(buffer.toString());
                                }
                                webSocket.request(1);
                                return null;
                            }

                            @Override
                            public void onError(WebSocket webSocket, Throwable error) {
                                received.completeExceptionally(error);
                            }
                        })
                .get(timeout.toSeconds(), TimeUnit.SECONDS);
        try {
            return received.get(timeout.toSeconds(), TimeUnit.SECONDS);
        } finally {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "done");
        }
    }

    private static String id() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static String auth(String region) {
        return "AWS4-HMAC-SHA256 Credential=AKID/20260205/" + region + "/ivschat/aws4_request";
    }
}
