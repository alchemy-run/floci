package io.floci.aws.services.ivschat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Amazon IVS Chat: rooms, logging configurations and tags over REST, and the messaging WebSocket a chat token
 * connects, with capabilities, message limits, events, deletions, disconnections and logging to CloudWatch Logs and
 * S3.
 */
@QuarkusTest
class IvsChatIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ACCOUNT = "000000000000";

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    static ValidatableResponse chat(String operation, String body) {
        return given().header("Authorization", "AWS4-HMAC-SHA256 Credential=" + ACCOUNT + "/20260101/us-east-1/"
                + "ivschat/aws4_request").contentType("application/json").body(body).post("/" + operation).then();
    }

    private static ValidatableResponse logs(String target, String body) {
        return given().header("Authorization", "AWS4-HMAC-SHA256 Credential=" + ACCOUNT + "/20260101/us-east-1/"
                        + "logs/aws4_request").header("X-Amz-Target", "Logs_20140328." + target)
                .contentType("application/x-amz-json-1.1").body(body).post("/").then();
    }

    private static String s3Auth() {
        return "AWS4-HMAC-SHA256 Credential=" + ACCOUNT + "/20260101/us-east-1/s3/aws4_request";
    }

    private static String token(String room, String user, String capabilities) {
        return chat("CreateChatToken", "{\"roomIdentifier\":\"" + room + "\",\"userId\":\"" + user
                + "\",\"capabilities\":" + capabilities + ",\"attributes\":{\"displayName\":\"" + user + "\"}}")
                .statusCode(200).extract().path("token");
    }

    /** A chat connection that queues every message the room sends it. */
    static final class Client implements WebSocket.Listener {
        final BlockingQueue<JsonNode> frames = new LinkedBlockingQueue<>();
        final CompletableFuture<Integer> closed = new CompletableFuture<>();
        private final StringBuilder partial = new StringBuilder();
        WebSocket socket;

        static Client connect(String token) throws Exception {
            Client client = new Client();
            client.socket = HttpClient.newHttpClient().newWebSocketBuilder().subprotocols(token)
                    .buildAsync(URI.create("ws://localhost:" + RestAssured.port + "/_floci/ivschat/us-east-1"), client)
                    .get(10, TimeUnit.SECONDS);
            return client;
        }

        void send(String json) {
            socket.sendText(json, true).join();
        }

        JsonNode next() throws InterruptedException {
            JsonNode frame = frames.poll(60, TimeUnit.SECONDS);
            assertNotNull(frame, "no frame arrived");
            return frame;
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                try {
                    frames.add(MAPPER.readTree(partial.toString()));
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
                partial.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closed.complete(statusCode);
            return null;
        }
    }

    @Test
    void roomsLoggingConfigurationsAndTags() {
        String missing = "arn:aws:ivschat:us-east-1:" + ACCOUNT + ":room/abcdABCD1234";
        chat("GetRoom", "{\"identifier\":\"" + missing + "\"}").statusCode(404)
                .header("x-amzn-ErrorType", "ResourceNotFoundException")
                .body("message", containsString("not found")).body("resourceId", equalTo("abcdABCD1234"))
                .body("resourceType", equalTo("ROOM"));
        chat("CreateRoom", "{\"maximumMessageLength\":501}").statusCode(400)
                .header("x-amzn-ErrorType", "ValidationException").body("reason", equalTo("FIELD_VALIDATION_FAILED"));

        String room = chat("CreateRoom", "{\"name\":\"lobby\",\"tags\":{\"team\":\"chat\"}}").statusCode(200)
                .body("arn", matchesPattern("arn:aws:ivschat:us-east-1:" + ACCOUNT + ":room/[A-Za-z0-9]{12}"))
                .body("maximumMessageRatePerSecond", equalTo(10)).body("maximumMessageLength", equalTo(500))
                .body("tags.team", equalTo("chat")).extract().path("arn");
        chat("UpdateRoom", "{\"identifier\":\"" + room + "\",\"name\":\"hall\",\"maximumMessageLength\":300,"
                + "\"messageReviewHandler\":{\"uri\":\"arn:aws:lambda:us-east-1:" + ACCOUNT + ":function:review\"}}")
                .statusCode(200).body("name", equalTo("hall")).body("maximumMessageLength", equalTo(300))
                .body("messageReviewHandler.fallbackResult", equalTo("ALLOW"));
        chat("UpdateRoom", "{\"identifier\":\"" + room + "\",\"messageReviewHandler\":{\"uri\":\"arn:aws:lambda:"
                + "eu-west-1:" + ACCOUNT + ":function:review\"}}").statusCode(400);
        chat("ListRooms", "{\"messageReviewHandlerUri\":\"arn:aws:lambda:us-east-1:" + ACCOUNT + ":function:review\"}")
                .statusCode(200).body("rooms.arn", hasItem(room));
        chat("UpdateRoom", "{\"identifier\":\"" + room + "\",\"messageReviewHandler\":{\"uri\":\"\"}}")
                .statusCode(200).body("messageReviewHandler", equalTo(null));

        chat("CreateLoggingConfiguration", "{\"destinationConfiguration\":{\"cloudWatchLogs\":{\"logGroupName\":"
                + "\"/ivschat/missing\"}}}").statusCode(404).header("x-amzn-ErrorType", "ResourceNotFoundException");
        chat("CreateLoggingConfiguration", "{\"destinationConfiguration\":{}}").statusCode(400);
        logs("CreateLogGroup", "{\"logGroupName\":\"/ivschat/rooms\"}").statusCode(200);
        String logging = chat("CreateLoggingConfiguration", "{\"name\":\"rooms\",\"destinationConfiguration\":"
                + "{\"cloudWatchLogs\":{\"logGroupName\":\"/ivschat/rooms\"}},\"tags\":{\"k\":\"v\"}}").statusCode(200)
                .body("state", equalTo("ACTIVE")).body("arn", containsString(":logging-configuration/"))
                .extract().path("arn");
        chat("GetLoggingConfiguration", "{\"identifier\":\"" + logging + "\"}").statusCode(200)
                .body("destinationConfiguration.cloudWatchLogs.logGroupName", equalTo("/ivschat/rooms"))
                .body("state", equalTo("ACTIVE"));
        chat("UpdateRoom", "{\"identifier\":\"" + room + "\",\"loggingConfigurationIdentifiers\":[\"" + logging
                + "\"]}").statusCode(200).body("loggingConfigurationIdentifiers", hasItem(logging));
        chat("UpdateRoom", "{\"identifier\":\"" + room + "\",\"loggingConfigurationIdentifiers\":[\"arn:aws:"
                + "ivschat:us-east-1:" + ACCOUNT + ":logging-configuration/nopeNOPEnope\"]}").statusCode(404);
        chat("ListRooms", "{\"loggingConfigurationIdentifier\":\"" + logging + "\"}").statusCode(200)
                .body("rooms", hasSize(1)).body("rooms[0].arn", equalTo(room));
        chat("ListLoggingConfigurations", "{}").statusCode(200).body("loggingConfigurations.arn", hasItem(logging));
        chat("DeleteLoggingConfiguration", "{\"identifier\":\"" + logging + "\"}").statusCode(409)
                .header("x-amzn-ErrorType", "ConflictException");

        String encoded = URLEncoder.encode(room, StandardCharsets.UTF_8);
        String auth = "AWS4-HMAC-SHA256 Credential=" + ACCOUNT + "/20260101/us-east-1/ivschat/aws4_request";
        given().header("Authorization", auth).contentType("application/json").body("{\"tags\":{\"env\":\"dev\"}}")
                .post("/tags/" + encoded).then().statusCode(200);
        given().header("Authorization", auth).queryParam("tagKeys", "team").delete("/tags/" + encoded)
                .then().statusCode(200);
        given().header("Authorization", auth).get("/tags/" + encoded).then().statusCode(200)
                .body("tags.env", equalTo("dev")).body("tags.team", equalTo(null));

        chat("DeleteRoom", "{\"identifier\":\"" + room + "\"}").statusCode(200);
        chat("DeleteLoggingConfiguration", "{\"identifier\":\"" + logging + "\"}").statusCode(200);
        chat("GetLoggingConfiguration", "{\"identifier\":\"" + logging + "\"}").statusCode(404);
    }

    @Test
    void messagingThroughTheRoom() throws Exception {
        logs("CreateLogGroup", "{\"logGroupName\":\"/ivschat/messaging\"}").statusCode(200);
        given().header("Authorization", s3Auth()).put("/ivschat-messaging-logs").then().statusCode(200);
        String cloudWatch = chat("CreateLoggingConfiguration", "{\"destinationConfiguration\":{\"cloudWatchLogs\":"
                + "{\"logGroupName\":\"/ivschat/messaging\"}}}").statusCode(200).extract().path("arn");
        String s3 = chat("CreateLoggingConfiguration", "{\"destinationConfiguration\":{\"s3\":{\"bucketName\":"
                + "\"ivschat-messaging-logs\"}}}").statusCode(200).extract().path("arn");
        String room = chat("CreateRoom", "{\"maximumMessageLength\":20,\"loggingConfigurationIdentifiers\":[\""
                + cloudWatch + "\",\"" + s3 + "\"]}").statusCode(200).extract().path("arn");
        String roomId = room.substring(room.lastIndexOf('/') + 1);

        String aliceToken = token(room, "alice", "[\"SEND_MESSAGE\"]");
        Client alice = Client.connect(aliceToken);
        assertEquals(aliceToken, alice.socket.getSubprotocol());
        Client viewer = Client.connect(token(room, "viewer", "[]"));
        Client moderator = Client.connect(token(room, "mod", "[\"DELETE_MESSAGE\",\"DISCONNECT_USER\"]"));

        ExecutionException reused = assertThrows(ExecutionException.class,
                () -> Client.connect(aliceToken));
        assertInstanceOf(WebSocketHandshakeException.class, reused.getCause());
        assertEquals(401, ((WebSocketHandshakeException) reused.getCause()).getResponse().statusCode());

        alice.send("{\"Action\":\"SEND_MESSAGE\",\"RequestId\":\"r1\",\"Content\":\"hello\","
                + "\"Attributes\":{\"color\":\"red\"}}");
        for (Client client : new Client[] {alice, viewer, moderator}) {
            JsonNode message = client.next();
            assertEquals("MESSAGE", message.path("Type").asText());
            assertEquals("hello", message.path("Content").asText());
            assertEquals("r1", message.path("RequestId").asText());
            assertEquals("red", message.path("Attributes").path("color").asText());
            assertEquals("alice", message.path("Sender").path("UserId").asText());
            assertEquals("alice", message.path("Sender").path("Attributes").path("displayName").asText());
            assertTrue(message.path("Id").asText().matches("[A-Za-z0-9]{12}"));
        }

        viewer.send("{\"Action\":\"SEND_MESSAGE\",\"RequestId\":\"r2\",\"Content\":\"hi\"}");
        JsonNode forbidden = viewer.next();
        assertEquals("ERROR", forbidden.path("Type").asText());
        assertEquals(403, forbidden.path("ErrorCode").asInt());
        assertEquals("r2", forbidden.path("RequestId").asText());

        alice.send("{\"Action\":\"SEND_MESSAGE\",\"Content\":\"this message is far too long\"}");
        assertEquals(400, alice.next().path("ErrorCode").asInt());
        alice.send("{\"Action\":\"NOPE\"}");
        assertEquals(400, alice.next().path("ErrorCode").asInt());

        String eventId = chat("SendEvent", "{\"roomIdentifier\":\"" + room + "\",\"eventName\":\"app:joined\","
                + "\"attributes\":{\"who\":\"bob\"}}").statusCode(200).extract().path("id");
        JsonNode event = viewer.next();
        assertEquals("EVENT", event.path("Type").asText());
        assertEquals("app:joined", event.path("EventName").asText());
        assertEquals(eventId, event.path("Id").asText());
        assertEquals("bob", event.path("Attributes").path("who").asText());
        alice.next();
        moderator.next();

        moderator.send("{\"Action\":\"DELETE_MESSAGE\",\"Id\":\"" + eventId + "\",\"Reason\":\"spam\","
                + "\"RequestId\":\"d1\"}");
        JsonNode deleted = alice.next();
        assertEquals("aws:DELETE_MESSAGE", deleted.path("EventName").asText());
        assertEquals(eventId, deleted.path("Attributes").path("MessageId").asText());
        assertEquals(eventId, deleted.path("Attributes").path("MessageID").asText());
        assertEquals("spam", deleted.path("Attributes").path("Reason").asText());
        assertEquals("d1", deleted.path("RequestId").asText());
        viewer.next();
        moderator.next();
        chat("DeleteMessage", "{\"roomIdentifier\":\"" + room + "\",\"id\":\"" + eventId + "\"}").statusCode(200)
                .body("id", matchesPattern("[A-Za-z0-9]{12}"));
        assertEquals("aws:DELETE_MESSAGE", viewer.next().path("EventName").asText());

        moderator.send("{\"Action\":\"DISCONNECT_USER\",\"UserId\":\"alice\",\"Reason\":\"bye\"}");
        assertEquals(1000, alice.closed.get(10, TimeUnit.SECONDS));
        chat("DisconnectUser", "{\"roomIdentifier\":\"" + room + "\",\"userId\":\"viewer\"}").statusCode(200);
        assertEquals(1000, viewer.closed.get(10, TimeUnit.SECONDS));

        // Everything delivered is logged: the message, the event and both deletions.
        String stream = "aws/IVSChatLogs/1.0/room_" + roomId;
        JsonNode logged = MAPPER.createObjectNode();
        for (int attempt = 0; attempt < 50 && logged.path("events").size() < 4; attempt++) {
            Thread.sleep(attempt == 0 ? 0 : 100);
            logged = MAPPER.readTree(logs("GetLogEvents", "{\"logGroupName\":\"/ivschat/messaging\","
                    + "\"logStreamName\":\"" + stream + "\"}").extract().asString());
        }
        assertEquals(4, logged.path("events").size(), logged.toString());
        JsonNode first = MAPPER.readTree(logged.path("events").get(0).path("message").asText());
        assertEquals("MESSAGE", first.path("type").asText());
        assertEquals("1.0", first.path("version").asText());
        assertEquals("hello", first.path("payload").path("Content").asText());

        String listing = "";
        for (int attempt = 0; attempt < 50 && listing.split("<Key>").length < 5; attempt++) {
            Thread.sleep(attempt == 0 ? 0 : 100);
            listing = given().header("Authorization", s3Auth()).queryParam("list-type", "2")
                    .queryParam("prefix", "AWSLogs/" + ACCOUNT + "/IVSChatLogs/1.0/us-east-1/room_" + roomId + "/")
                    .get("/ivschat-messaging-logs").then().statusCode(200).extract().asString();
        }
        Matcher keys = Pattern.compile("<Key>([^<]+)</Key>").matcher(listing);
        StringBuilder lines = new StringBuilder();
        int objects = 0;
        while (keys.find()) {
            objects++;
            String key = keys.group(1);
            assertTrue(key.matches("AWSLogs/" + ACCOUNT + "/IVSChatLogs/1\\.0/us-east-1/room_" + roomId
                    + "/\\d{4}/\\d{2}/\\d{2}/\\d{2}/" + ACCOUNT + "_IVSChatLogs_1\\.0_us-east-1_room_" + roomId
                    + "_\\d{8}T\\d{4}Z_[a-z0-9]{8}\\.log\\.gz"), key);
            byte[] body = given().header("Authorization", s3Auth()).get("/ivschat-messaging-logs/" + key).then()
                    .statusCode(200).extract().asByteArray();
            try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(body))) {
                lines.append(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        assertEquals(4, objects, listing);
        assertTrue(lines.toString().contains("\"Content\":\"hello\""), lines.toString());
        moderator.socket.sendClose(WebSocket.NORMAL_CLOSURE, "");
    }

    @Test
    void theRoomsMessageRateIsEnforced() throws Exception {
        String room = chat("CreateRoom", "{\"maximumMessageRatePerSecond\":1}").statusCode(200).extract().path("arn");
        Client sender = Client.connect(token(room, "fast", "[\"SEND_MESSAGE\"]"));
        sender.send("{\"Action\":\"SEND_MESSAGE\",\"Content\":\"one\"}");
        sender.send("{\"Action\":\"SEND_MESSAGE\",\"Content\":\"two\"}");
        assertEquals("MESSAGE", sender.next().path("Type").asText());
        assertEquals(429, sender.next().path("ErrorCode").asInt());
        chat("DeleteRoom", "{\"identifier\":\"" + room + "\"}").statusCode(200);
        assertEquals(1000, sender.closed.get(10, TimeUnit.SECONDS));
    }

    @Test
    void theEdgeHostAnswersTheHandshakeWithTheToken() throws Exception {
        String room = chat("CreateRoom", "{}").statusCode(200).extract().path("arn");
        String token = token(room, "raw", "[]");
        assertEquals("401", handshake("edge.ivschat.eu-west-1.localhost.floci.io", token(room, "other", "[]"))[0]);
        String[] answer = handshake("edge.ivschat.us-east-1.localhost.floci.io", token);
        assertEquals("101", answer[0]);
        assertTrue(answer[1].contains("Sec-WebSocket-Protocol: " + token), answer[1]);
        assertTrue(answer[1].contains("Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo="), answer[1]);
        chat("DeleteRoom", "{\"identifier\":\"" + room + "\"}").statusCode(200);
    }

    /** The status and head of a raw handshake on a Host, with RFC 6455's sample key. */
    private static String[] handshake(String host, String token) throws IOException {
        try (Socket socket = new Socket("localhost", RestAssured.port)) {
            socket.setSoTimeout((int) Duration.ofSeconds(10).toMillis());
            OutputStream out = socket.getOutputStream();
            out.write(("GET / HTTP/1.1\r\nHost: " + host + "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n"
                    + "Sec-WebSocket-Protocol: " + token + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = socket.getInputStream();
            StringBuilder head = new StringBuilder();
            while (!head.toString().endsWith("\r\n\r\n")) {
                int read = in.read();
                if (read < 0) {
                    break;
                }
                head.append((char) read);
            }
            return new String[] {head.substring(9, 12), head.toString()};
        }
    }
}
